package com.openvpp.app.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 贯穿案例持久化仓库 —— 教学默认 JdbcTemplate + H2 文件库，docker 交付切换 MySQL。
 * response_id 是任务→指令→基线→账单的统一关联标识。
 *
 * 方言自适应：H2 幂等 upsert 用 MERGE INTO .. KEY(..)，MySQL 同语义为
 * INSERT .. ON DUPLICATE KEY UPDATE（第 25 篇交付的真实坑：两库不能共用一句 SQL）。
 * 默认按连接元数据自动探测，openvpp.db.dialect=mysql|h2 可显式指定（测试用）。
 *
 * 第 3 轮复核并发修复：
 *   claimTask  事务内 INSERT 原子认领任务（唯一键即认领锁，同键并发只有一个成功）；
 *   updateTaskOutcome  认领后 UPDATE 落定终态/缺口，不再整行 MERGE 覆盖；
 *   insertBill 审计账单 INSERT-only，绝不覆盖既有主键行（争议更正历史版本不可变）；
 *   dispute_correction 留档表唯一键 (response_id, correction_request_id) 拦截同键重复提交；
 *   lockTaskRow SELECT .. FOR UPDATE 串行化同一任务的并发版本分配。
 */
@Repository
public class ResponseRepository {

    private final JdbcTemplate jdbc;
    /** 方言覆盖：auto（默认，按数据源 URL 探测）/ mysql / h2 */
    private final String dialectConfig;
    /** 数据源 URL：auto 模式下按 "jdbc:mysql" 前缀判定方言（禁止另开连接探测——
     *  高并发认领阻塞时会占满连接池，持有连接的执行者再排队等探测连接即连接池自锁） */
    private final String datasourceUrl;
    private volatile Boolean mysqlDialect;

    public ResponseRepository(JdbcTemplate jdbc,
                              @Value("${openvpp.db.dialect:auto}") String dialectConfig,
                              @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.jdbc = jdbc;
        this.dialectConfig = dialectConfig;
        this.datasourceUrl = datasourceUrl;
    }

    private boolean isMysql() {
        if ("mysql".equalsIgnoreCase(dialectConfig)) {
            return true;
        }
        if ("h2".equalsIgnoreCase(dialectConfig)) {
            return false;
        }
        Boolean flag = mysqlDialect;
        if (flag == null) {
            // URL 判定为纯字符串读取，无锁无连接；并发首判重复赋值结果一致
            flag = datasourceUrl != null && datasourceUrl.contains(":mysql:");
            mysqlDialect = flag;
        }
        return flag;
    }

    // ---------- 任务 ----------

    private static final String TASK_UPSERT_COLUMNS =
            "response_id,event_id,declared_kwh,target_kw,window_start,window_end,state,gap_kw,created_ms";

    public void saveTask(String responseId, String eventId, BigDecimal declaredKwh,
                         BigDecimal targetKw, long windowStart, long windowEnd,
                         String state, BigDecimal gapKw) {
        String sql = isMysql()
                ? "INSERT INTO dr_task (" + TASK_UPSERT_COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE event_id=VALUES(event_id), declared_kwh=VALUES(declared_kwh), "
                        + "target_kw=VALUES(target_kw), window_start=VALUES(window_start), window_end=VALUES(window_end), "
                        + "state=VALUES(state), gap_kw=VALUES(gap_kw), created_ms=VALUES(created_ms)"
                : "MERGE INTO dr_task KEY(response_id) VALUES(?,?,?,?,?,?,?,?,?)";
        jdbc.update(sql, responseId, eventId, declaredKwh, targetKw, windowStart, windowEnd,
                state, gapKw, System.currentTimeMillis());
    }

    /**
     * 原子认领：事务内 INSERT，response_id 唯一主键即认领锁。
     * 同键并发时后到者在唯一索引上阻塞，持有方提交后其收到重复键（转幂等重放），
     * 持有方回滚则该行消失、后到者自然接管重新认领。
     * state 写入占位值 DISPATCHED（终态由 updateTaskOutcome 落定；占位态在事务提交前对外不可见）。
     */
    public void claimTask(String responseId, String eventId, BigDecimal declaredKwh,
                          BigDecimal targetKw, long windowStart, long windowEnd) {
        jdbc.update("INSERT INTO dr_task (" + TASK_UPSERT_COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?)",
                responseId, eventId, declaredKwh, targetKw, windowStart, windowEnd,
                "DISPATCHED", BigDecimal.ZERO, System.currentTimeMillis());
    }

    /** 认领后落定任务结果（终态/缺口），不整行覆盖 */
    public void updateTaskOutcome(String responseId, String state, BigDecimal gapKw) {
        jdbc.update("UPDATE dr_task SET state=?, gap_kw=? WHERE response_id=?", state, gapKw, responseId);
    }

    /** 删除任务行（GAP 终态重跑 / 无实收 DISPATCHED 残留的接管清理），返回删除行数 */
    public int deleteTask(String responseId) {
        return jdbc.update("DELETE FROM dr_task WHERE response_id=?", responseId);
    }

    /**
     * 串行化锁：锁定任务行直到本事务结束，同一任务的并发争议更正在此排队，
     * 保证「读最新版本号 → 分配下一版本 → 写账单」整体原子（H2/MySQL 均支持 FOR UPDATE）。
     */
    public void lockTaskRow(String responseId) {
        jdbc.queryForList("SELECT response_id FROM dr_task WHERE response_id=? FOR UPDATE", responseId);
    }

    public void updateTaskState(String responseId, String state) {
        jdbc.update("UPDATE dr_task SET state=? WHERE response_id=?", state, responseId);
    }

    public boolean taskExists(String responseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dr_task WHERE response_id=?", Integer.class, responseId);
        return n != null && n > 0;
    }

    /** 任务行数（并发测试断言：同键并发后必须恰好 1 行） */
    public int countTasks(String responseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dr_task WHERE response_id=?", Integer.class, responseId);
        return n == null ? 0 : n;
    }

    /** 任务状态（DISPATCHED / SETTLED / GAP），任务不存在返回 null */
    public String taskState(String responseId) {
        List<String> states = jdbc.query(
                "SELECT state FROM dr_task WHERE response_id=?",
                (rs, rowNum) -> rs.getString(1), responseId);
        return states.isEmpty() ? null : states.get(0);
    }

    public List<Map<String, Object>> listTasks() {
        return jdbc.queryForList("SELECT * FROM dr_task ORDER BY created_ms DESC");
    }

    // ---------- 指令 ----------

    public void saveInstruction(String instructionId, String responseId, String resourceId,
                                BigDecimal commandKw, String state, Long sentMs, Long reachedMs) {
        String sql = isMysql()
                ? "INSERT INTO dispatch_instruction (instruction_id,response_id,resource_id,command_kw,state,sent_ms,reached_ms) "
                        + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE response_id=VALUES(response_id), "
                        + "resource_id=VALUES(resource_id), command_kw=VALUES(command_kw), state=VALUES(state), "
                        + "sent_ms=VALUES(sent_ms), reached_ms=VALUES(reached_ms)"
                : "MERGE INTO dispatch_instruction KEY(instruction_id) VALUES(?,?,?,?,?,?,?)";
        jdbc.update(sql, instructionId, responseId, resourceId, commandKw, state, sentMs, reachedMs);
    }

    public void updateInstructionState(String instructionId, String state, Long reachedMs) {
        jdbc.update("UPDATE dispatch_instruction SET state=?, reached_ms=? WHERE instruction_id=?",
                state, reachedMs, instructionId);
    }

    public List<Map<String, Object>> listInstructions(String responseId) {
        return responseId == null
                ? jdbc.queryForList("SELECT * FROM dispatch_instruction ORDER BY sent_ms DESC")
                : jdbc.queryForList("SELECT * FROM dispatch_instruction WHERE response_id=? ORDER BY sent_ms", responseId);
    }

    // ---------- 基线 ----------

    public void saveBaselinePoint(String responseId, int pointIndex, String ruleVersion,
                                  BigDecimal baselineKw, BigDecimal actualKw) {
        String sql = isMysql()
                ? "INSERT INTO baseline_record (response_id,point_index,rule_version,baseline_kw,actual_kw,computed_ms) "
                        + "VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE rule_version=VALUES(rule_version), "
                        + "baseline_kw=VALUES(baseline_kw), actual_kw=VALUES(actual_kw), computed_ms=VALUES(computed_ms)"
                : "MERGE INTO baseline_record KEY(response_id, point_index) VALUES(?,?,?,?,?,?)";
        jdbc.update(sql, responseId, pointIndex, ruleVersion, baselineKw, actualKw, System.currentTimeMillis());
    }

    public List<Map<String, Object>> listBaselines(String responseId) {
        return jdbc.queryForList(
                "SELECT * FROM baseline_record WHERE response_id=? ORDER BY point_index", responseId);
    }

    // ---------- 账单 ----------

    private static final String BILL_COLUMNS =
            "response_id,subject,amount_yuan,bill_type,bill_version,memo,created_ms";

    /**
     * 账单落库（幂等 upsert）：主键含账期版本（bill_version）——
     * 原始出账写 V1，同版本恢复重写用；争议更正历史版本一律走 insertBill（INSERT-only）。
     */
    public void saveBill(String responseId, String subject, BigDecimal amountYuan,
                         String billType, String billVersion, String memo) {
        String sql = isMysql()
                ? "INSERT INTO bill (" + BILL_COLUMNS + ") VALUES(?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE amount_yuan=VALUES(amount_yuan), memo=VALUES(memo), created_ms=VALUES(created_ms)"
                : "MERGE INTO bill KEY(response_id, subject, bill_type, bill_version) VALUES(?,?,?,?,?,?,?)";
        jdbc.update(sql, responseId, subject, amountYuan, billType, billVersion, memo, System.currentTimeMillis());
    }

    /**
     * 审计账单 INSERT-only：主键冲突即抛错，绝不覆盖既有行。
     * 争议更正产生的全部更正版本账单（SETTLE/PENALTY/CORRECTION/PLATFORM_CUT/SHARE）
     * 必须走本方法——审计留痕一旦写入不可变，历史版本禁止 MERGE 原地覆盖。
     */
    public void insertBill(String responseId, String subject, BigDecimal amountYuan,
                           String billType, String billVersion, String memo) {
        jdbc.update("INSERT INTO bill (" + BILL_COLUMNS + ") VALUES(?,?,?,?,?,?,?)",
                responseId, subject, amountYuan, billType, billVersion, memo, System.currentTimeMillis());
    }

    public boolean billExists(String responseId, String subject, String billType) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bill WHERE response_id=? AND subject=? AND bill_type=?",
                Integer.class, responseId, subject, billType);
        return n != null && n > 0;
    }

    /**
     * 删除指定账期版本的分摊类账单（SHARE / PLATFORM_CUT），保留收入侧（SETTLE）、
     * 考核扣款（PENALTY）与全部历史版本更正记录。
     * 供 V1 同版本分摊"全量替换"式恢复重写使用；争议更正写新版本走 INSERT-only，不经过本方法。
     */
    public int deleteAllocationBills(String responseId, String billVersion) {
        return jdbc.update(
                "DELETE FROM bill WHERE response_id=? AND bill_version=? AND bill_type IN ('SHARE','PLATFORM_CUT')",
                responseId, billVersion);
    }

    public List<Map<String, Object>> listBills(String responseId) {
        return responseId == null
                ? jdbc.queryForList("SELECT * FROM bill ORDER BY created_ms DESC")
                : jdbc.queryForList("SELECT * FROM bill WHERE response_id=? ORDER BY subject, bill_type", responseId);
    }

    /** 资金守恒核对：某响应下全部账单之和（跨版本合计仅审计参考，守恒判定用版本口径） */
    public BigDecimal billSum(String responseId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_yuan),0) FROM bill WHERE response_id=?",
                BigDecimal.class, responseId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 清理全部演示数据（重置用），返回删除的总行数 */
    public int deleteAll() {
        int n = jdbc.update("DELETE FROM bill");
        n += jdbc.update("DELETE FROM dispute_correction");
        n += jdbc.update("DELETE FROM baseline_record");
        n += jdbc.update("DELETE FROM dispatch_instruction");
        n += jdbc.update("DELETE FROM dr_task");
        return n;
    }

    /** 分配侧合计（指定版本：SHARE + PLATFORM_CUT），守恒核对用：应等于同版本净实收（SETTLE） */
    public BigDecimal allocationSum(String responseId, String billVersion) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_yuan),0) FROM bill WHERE response_id=? AND bill_version=? "
                        + "AND bill_type IN ('SHARE','PLATFORM_CUT')",
                BigDecimal.class, responseId, billVersion);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 最新版本解析：按版本号数值降序（先比长度再比字典序，V10 > V9 > V2），同毫秒写入不再依赖
     * created_ms 定序——并发更正下 created_ms 相同曾导致「最新版本」取错、差额算错。
     */
    private static final String LATEST_VERSION_ORDER =
            "ORDER BY LENGTH(bill_version) DESC, bill_version DESC, created_ms DESC LIMIT 1";

    /** 收入侧净实收（SETTLE）：取最新账期版本（原始 V1，多轮更正后为最新版本） */
    public BigDecimal settleAmount(String responseId) {
        List<BigDecimal> amounts = jdbc.query(
                "SELECT amount_yuan FROM bill WHERE response_id=? AND subject='PLATFORM' AND bill_type='SETTLE' "
                        + LATEST_VERSION_ORDER,
                (rs, rowNum) -> rs.getBigDecimal(1), responseId);
        return amounts.isEmpty() ? BigDecimal.ZERO : amounts.get(0);
    }

    /** 指定版本的净实收（SETTLE），无记录返回 null（纠偏幂等重放用） */
    public BigDecimal settleAmountOfVersion(String responseId, String billVersion) {
        List<BigDecimal> amounts = jdbc.query(
                "SELECT amount_yuan FROM bill WHERE response_id=? AND subject='PLATFORM' AND bill_type='SETTLE' "
                        + "AND bill_version=?",
                (rs, rowNum) -> rs.getBigDecimal(1), responseId, billVersion);
        return amounts.isEmpty() ? null : amounts.get(0);
    }

    /** 指定版本的用户分摊（SHARE），纠偏幂等重放还原结果用 */
    public Map<String, BigDecimal> allocationOfVersion(String responseId, String billVersion) {
        Map<String, BigDecimal> allocation = new LinkedHashMap<>();
        RowCallbackHandler mapper = rs -> allocation.put(rs.getString(1), rs.getBigDecimal(2));
        jdbc.query(
                "SELECT subject, amount_yuan FROM bill WHERE response_id=? AND bill_version=? AND bill_type='SHARE'",
                mapper, responseId, billVersion);
        return allocation;
    }

    /** 某类账单的最新账期版本号（如 V3），无记录返回 null；争议更正据此递增下一版本 */
    public String latestBillVersion(String responseId, String subject, String billType) {
        List<String> versions = jdbc.query(
                "SELECT bill_version FROM bill WHERE response_id=? AND subject=? AND bill_type=? "
                        + LATEST_VERSION_ORDER,
                (rs, rowNum) -> rs.getString(1), responseId, subject, billType);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /** 任务的申报响应电量（争议更正以任务落库值为准，不信任调用方重复传参） */
    public BigDecimal taskDeclaredKwh(String responseId) {
        List<BigDecimal> values = jdbc.query(
                "SELECT declared_kwh FROM dr_task WHERE response_id=?",
                (rs, rowNum) -> rs.getBigDecimal(1), responseId);
        return values.isEmpty() ? null : values.get(0);
    }

    // ---------- 争议更正留档 ----------

    /**
     * 登记纠偏请求：唯一键 (response_id, correction_request_id)。
     * 同键重复提交（含并发）在此被数据库拦截——先占键再写账单，重复方收到重复键后
     * 读取已留档版本返回原结果。并发同键请求只能有一个完成出账。
     */
    public void saveCorrectionRecord(String responseId, String correctionRequestId, String billVersion,
                                     BigDecimal correctedActualKw, BigDecimal diffYuan) {
        jdbc.update("INSERT INTO dispute_correction "
                        + "(response_id,correction_request_id,bill_version,corrected_actual_kw,diff_yuan,created_ms) "
                        + "VALUES(?,?,?,?,?,?)",
                responseId, correctionRequestId, billVersion, correctedActualKw, diffYuan,
                System.currentTimeMillis());
    }

    /** 纠偏请求留档记录（幂等重放还原原结果用） */
    public static final class CorrectionRecord {
        private final String billVersion;
        private final BigDecimal correctedActualKw;
        private final BigDecimal diffYuan;

        public CorrectionRecord(String billVersion, BigDecimal correctedActualKw, BigDecimal diffYuan) {
            this.billVersion = billVersion;
            this.correctedActualKw = correctedActualKw;
            this.diffYuan = diffYuan;
        }

        public String getBillVersion() { return billVersion; }
        public BigDecimal getCorrectedActualKw() { return correctedActualKw; }
        public BigDecimal getDiffYuan() { return diffYuan; }
    }

    /** 查纠偏请求留档，未登记返回 null */
    public CorrectionRecord findCorrection(String responseId, String correctionRequestId) {
        List<CorrectionRecord> records = jdbc.query(
                "SELECT bill_version,corrected_actual_kw,diff_yuan FROM dispute_correction "
                        + "WHERE response_id=? AND correction_request_id=?",
                (rs, rowNum) -> new CorrectionRecord(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                responseId, correctionRequestId);
        return records.isEmpty() ? null : records.get(0);
    }

    /** 某任务的纠偏请求留档条数（并发测试断言：N 个不同请求恰好 N 条） */
    public int countCorrections(String responseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispute_correction WHERE response_id=?", Integer.class, responseId);
        return n == null ? 0 : n;
    }
}
