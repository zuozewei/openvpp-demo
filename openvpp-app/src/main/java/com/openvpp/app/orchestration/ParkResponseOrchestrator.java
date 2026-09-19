package com.openvpp.app.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvpp.aggregator.engine.AssessedResource;
import com.openvpp.aggregator.engine.CapacityPoolCalculator;
import com.openvpp.aggregator.engine.CapacityReservationLedger;
import com.openvpp.aggregator.engine.InstructionDecomposer;
import com.openvpp.aggregator.engine.TaskWindow;
import com.openvpp.app.idempotency.IdempotencyGuard;
import com.openvpp.app.persistence.ResponseRepository;
import com.openvpp.dispatch.instruction.DispatchInstruction;
import com.openvpp.dispatch.instruction.InstructionRepository;
import com.openvpp.dispatch.instruction.InstructionService;
import com.openvpp.settlement.allocation.ProfitAllocator;
import com.openvpp.settlement.assessment.DeviationAssessor;
import com.openvpp.settlement.baseline.BaselineCalculator;
import com.openvpp.settlement.baseline.BaselineRule;
import com.openvpp.settlement.baseline.MeteringResult;
import com.openvpp.settlement.baseline.ResponseMetering;
import com.openvpp.settlement.baseline.SamplePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 园区需求响应编排服务 —— 把独立模块串成完整业务闭环。
 *
 * 链路：模拟遥测 → 接入校验 → 数据入库 → 能力评估 → 资源聚合 → 响应任务 →
 *       指令下发 → 执行核验 → 响应量计算 → 结算分摊 → 账单查询。
 *
 * 教学假设（全部数值为教学设定，不对应任何地区准入或结算规则）：
 *   响应窗口 14:00—15:00；15 分钟粒度 4 时段；各时段基线 1000 kW、实测 400 kW；
 *   响应电量 600×1h = 600 kWh；补偿单价 2 元/kWh。
 *   结算四量口径（毛额 → 考核 → 净实收 → 可分配，全链路统一计算）：
 *     补偿毛额 = 结算电量 × 单价；
 *     考核扣款 = 偏差考核费用（DeviationAssessor，≥90% 不考核）；
 *     平台净实收 = max(0, 毛额 − 考核扣款)——考核从应收补贴中扣除，不只进日志；
 *     可分配金额 = 净实收（保底 50% 按申报容量占比分配；平台服务费对扣除保底后的
 *     剩余部分收 10%；用户可分配 = 保底 + 分成池）。
 *   默认案例（申报 600）考核为 0：毛额 = 净实收 = 1200 元，用户可分配 1140 元、
 *   平台服务费 60 元（与 PARK-DEMO.md 手工核算底稿第四节逐项一致）；
 *   非零考核案例（申报 800、实际 600）：考核 400 元，净实收 800 元，
 *   保底 400 元、服务费 40 元、用户可分配 760 元（集成测试逐项断言）。
 *
 * 数据口径：遥测/计量/容量均为内置模拟源（buildSamples/buildMembers 直接构造），
 *       不来自网关真实接入；默认 openvpp.gateway.mode=local，零外部依赖。
 *
 * 幂等三态：任务状态 DISPATCHED（处理中）/ SETTLED（已结算）/ GAP（不可行）。
 *   同一 responseId 重复触发：SETTLED 直接返回不重复出账——唯一例外是
 *   path=DISPUTED，视为对该已结算任务的争议更正请求，转入更正流程；
 *   DISPATCHED 不重复下发指令，但继续执行结算——修复"实收先写、分摊后写、
 *   中途异常后重跑被误判幂等、分摊永远为 0"的缺陷：检测到实收已写而分摊缺失时
 *   走恢复路径补齐，分摊补齐全量替换（先删后写）保证金额不重复、可修半成品，
 *   最终做资金守恒核对。
 *
 * 并发口径（第 3 轮复核修复：同键并发曾全部进入首次执行，出现重复调度、
 *   重复出账与容量台账 CME）：幂等正确性由数据库承担——
 *   ① 事务内原子认领：run 入口 INSERT dr_task，response_id 唯一主键即认领锁；
 *     同键并发的后到者在唯一索引上阻塞，持有方提交后其收到重复键 → 读终态转幂等重放；
 *     持有方回滚则行消失，后到者自然接管重新认领（回滚不留残状态）。
 *   ② Redis 前置幂等缓存（仅 docker 交付启用）：已完成结果的快速重放不落库；
 *     本地教学/测试为 Noop 直通。Redis 只做加速，不是正确性来源。
 *   ③ 容量预占台账全方法互斥（消灭 occupiedIn 并发遍历 CME）；
 *     原子认领保证同一任务同一时刻只有一个执行实例，补偿按任务标识释放不会误删他任务预占。
 *
 * Redis 缓存时序与故障口径（第 4 轮复审修复）：
 *   ① 缓存写入挂到数据库事务提交之后（事务同步 afterCommit）——提交阶段失败
 *     只会释放在途标记，绝不留下「缓存成功、数据库回滚」的虚假成功结果；
 *   ② GAP（不可行）结果不缓存、在途标记立即释放，保留同键重跑语义；
 *   ③ 演示重置（resetRuntimeState）同步清空幂等缓存命名空间——否则重置后同键
 *     请求在 TTL 窗口内命中旧结果、不重新落库，与「重置后可再次运行」承诺冲突；
 *   ④ Redis 不可用的退化语义在守卫实现内部消化（读取未命中/登记放行/写清静默），
 *     业务层不感知连接故障。
 *
 * 争议更正（结算后独立入口 dispute()；run 的 path=DISPUTED 在任务已结算时
 *   等价转入同一流程）：计量补到数据（教学模拟第 2 时段实测修正，默认 400→380 kW，
 *   更正响应量 600 + (400−380)×0.25 = 605 kWh）到达后，按更正口径重算结算四量，
 *   生成**下一账期版本**的全套更正账单（SETTLE 净实收 / PENALTY 考核 /
 *   PLATFORM_CUT 服务费 / SHARE 分摊 / CORRECTION 冲正差额）——非零差额
 *   必须传导到服务费与分摊重算；账单主键含 bill_version，各版本独立留档
 *   互不覆盖，原始账单（V1）永不删除，支持同一任务多轮更正（V2、V3…）。
 *
 * 更正并发口径（第 3 轮复核修复：并发更正曾同时算出 V2 相互覆盖，12 路只留 3 版）：
 *   ① 请求幂等键：dispute() 携带 correctionRequestId，dispute_correction 留档表
 *     以 (response_id, correction_request_id) 唯一键拦截同键重复提交——先占键再写账单，
 *     重复方读取已留档版本返回原结果；
 *   ② 版本原子分配：写账单前 SELECT .. FOR UPDATE 锁任务行，同一任务的并发更正
 *     在事务层串行，「读最新版本 → 分配下一版本 → 写账单」整体原子；
 *   ③ 审计账单 INSERT-only：更正版本全部走 insertBill，主键冲突即抛错，杜绝 MERGE
 *     原地覆盖历史版本；
 *   ④ 最新版本解析按版本号数值降序（先比长度再比字典序），不再依赖同毫秒 created_ms；
 *   ⑤ 事务隔离显式 READ_COMMITTED（第 4 轮容器实测修复）：MySQL 默认 REPEATABLE READ
 *     下，FOR UPDATE 等锁结束后同事务的普通读仍走加锁前的旧快照，读到过期版本号、
 *     并发更正撞账单主键 500（真实 MySQL 上 12 路只出 3 版）；H2 默认即为
 *     READ_COMMITTED，故教学库测试未暴露。显式声明后锁下读均为最新已提交数据。
 *
 * 预占生命周期：指令分解登记容量预占（内存台账，按 responseId 标识）；
 *   任务正常完成后释放、取消/失败按任务标识释放、演示重置（resetRuntimeState）
 *   整体清空，避免内存台账与库表脱节导致重置后再运行误报缺口。
 *   异常兜底：入参先做前置校验（派生指令编号必须落在 instruction_id
 *   VARCHAR(64) 内，超长直接拒绝，不得等落库 500）；run 期间任何异常
 *   （典型如下发后落库失败触发数据库事务回滚）先执行内存态补偿——按任务
 *   释放预占、按派生编号前缀清除内存指令镜像，再抛出异常。数据库回滚救不了
 *   进程内状态，内存与库表必须同进同退，否则残留预占会把后续任务误报成缺口。
 */
@Service
public class ParkResponseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ParkResponseOrchestrator.class);

    // ---- 教学假设常量（与文章手工核算底稿一一对应） ----
    public static final double BASELINE_KW = 1000.0;
    public static final double ACTUAL_KW = 400.0;
    /** 争议路径教学设定：计量补到后第 2 时段（point_index=1）的修正实测 */
    public static final double DISPUTED_CORRECTED_KW = 380.0;
    public static final int POINTS = 4;                       // 4 个 15 分钟点
    public static final double INTERVAL_HOURS = 0.25;         // 15 分钟粒度
    public static final double PRICE_YUAN_PER_KWH = 2.0;      // 假设补偿单价
    public static final double PLATFORM_CUT_RATE = 0.10;      // 平台服务费 10%
    public static final String RULE_VERSION = "TEACH-2026.1";
    /** 账期版本号：原始出账 V1，此后每轮争议更正递增（V2、V3…），账单主键组成部分 */
    public static final String BILL_VERSION_ORIGINAL = "V1";
    /**
     * responseId 约束：4-50 位字母/数字/下划线/中划线——派生指令编号
     * responseId + "-ins-N" 必须落在 dispatch_instruction.instruction_id
     * VARCHAR(64) 内；超长编号入库即 500、事务回滚，必须在校验层先行拒绝。
     */
    public static final Pattern RESPONSE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,50}$");
    /** 纠偏请求幂等键约束：4-64 位（落在 dispute_correction.correction_request_id VARCHAR(64) 内） */
    public static final Pattern CORRECTION_REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,64}$");

    /** 同键认领竞争的重试上限：覆盖两类场景——持有方未提交（状态不可见，短暂自旋等待）、
     * GAP 终态重跑 / 无实收 DISPATCHED 残留的接管（清行后重试）。
     */
    private static final int CLAIM_ATTEMPTS = 20;
    private static final long CLAIM_RETRY_INTERVAL_MS = 50;

    /** Redis 幂等缓存键前缀（结果缓存 + 在途标记共用；演示重置按此前缀清空命名空间） */
    public static final String RUN_GUARD_KEY_PREFIX = "openvpp:idempotency:run:";

    private final ResponseRepository repo;
    private final InstructionService instructionService;
    private final InstructionRepository instructionRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;
    private final BaselineCalculator baselineCalculator = new BaselineCalculator(BaselineRule.teachingDefault());
    private final ResponseMetering metering = new ResponseMetering();
    private final DeviationAssessor assessor = DeviationAssessor.provincialDefault();
    private final ProfitAllocator allocator = new ProfitAllocator(BigDecimal.valueOf(PLATFORM_CUT_RATE));
    private final InstructionDecomposer decomposer = new InstructionDecomposer();
    private final CapacityPoolCalculator poolCalculator = new CapacityPoolCalculator(0.9);
    private final CapacityReservationLedger reservationLedger = new CapacityReservationLedger();

    public ParkResponseOrchestrator(ResponseRepository repo,
                                    InstructionService instructionService,
                                    InstructionRepository instructionRepository,
                                    IdempotencyGuard idempotencyGuard,
                                    ObjectMapper objectMapper) {
        this.repo = repo;
        this.instructionService = instructionService;
        this.instructionRepository = instructionRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 跑一遍园区需求响应闭环。
     *
     * @param responseId   贯穿案例关联标识（幂等键，4-50 位，派生指令编号须落在 VARCHAR(64) 内）
     * @param path         NORMAL / DEGRADED / DISPUTED（DISPUTED 在任务已结算时等价于争议更正入口）
     * @param declaredKwh  申报响应电量
     * @param targetKw     调度目标功率（下调）
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DemoRunResult run(String responseId, String path,
                             BigDecimal declaredKwh, BigDecimal targetKw) {
        validateRunInput(responseId, path, declaredKwh, targetKw);

        DemoRunResult result = new DemoRunResult();
        result.setResponseId(responseId);
        result.setPath(path);
        List<String> trace = new ArrayList<>();

        // ⓪ Redis 前置幂等缓存（仅 docker 交付启用；本地教学/测试为 Noop 直通）：
        // 已完成结果命中缓存直接重放、不落库。DISPUTED 是更正请求不是重放，不缓存。
        // 守卫内部消化 Redis 故障（读取失败=未命中、登记失败=放行），业务层无感。
        String guardKey = RUN_GUARD_KEY_PREFIX + responseId;
        boolean guardEngaged = idempotencyGuard.isEnabled() && !"DISPUTED".equals(path);
        if (guardEngaged) {
            DemoRunResult cachedReplay = replayFromCache(idempotencyGuard.cachedResult(guardKey), responseId);
            if (cachedReplay != null) {
                return cachedReplay;
            }
        }
        boolean guardHolding = guardEngaged && idempotencyGuard.tryBegin(guardKey);
        boolean guardSyncRegistered = false;

        // ① 数据库原子认领（唯一键 + 事务内 INSERT）——幂等正确性的最终保证，不依赖 Redis：
        // 同键并发后到者在唯一索引上阻塞，持有方提交后其收到重复键 → 读终态转重放；
        // 持有方回滚则行消失，后到者自然接管执行。
        long nowMs = System.currentTimeMillis();
        boolean recovery = false;
        int claimAttempts = 0;
        while (true) {
            try {
                repo.claimTask(responseId, "evt-" + responseId, declaredKwh, targetKw,
                        nowMs, nowMs + 3600_000);
                break;
            } catch (DuplicateKeyException e) {
                String committedState = repo.taskState(responseId);
                if ("SETTLED".equals(committedState)) {
                    if (guardHolding) {
                        idempotencyGuard.release(guardKey);
                    }
                    if ("DISPUTED".equals(path)) {
                        // 结算后争议不是幂等重放，而是对该任务的更正请求——
                        // 与独立入口 dispute() 走同一更正流程，修复"事后争议被已结算拦截"
                        trace.add("结算后争议：任务已结算（SETTLED），转入争议更正流程（独立入口 /demo/dispute 的等价路径）");
                        applyCorrection(responseId, DISPUTED_CORRECTED_KW, null, trace, result);
                        result.setTrace(trace);
                        return result;
                    }
                    result.setIdempotentReplay(true);
                    rebuildSettledReplay(responseId, trace, result);
                    log.warn("幂等拦截: {} 已结算，跳过重复执行，返回数据库重建的既有结果", responseId);
                    result.setTrace(trace);
                    return result;
                }
                boolean recoverable = "DISPATCHED".equals(committedState)
                        && repo.billExists(responseId, "PLATFORM", "SETTLE");
                if (recoverable) {
                    // 实收已写而任务仍为 DISPATCHED 的中断残留：接管走恢复路径补齐（不重复认领）
                    recovery = true;
                    trace.add("恢复路径：检测到实收已写而任务仍为 DISPATCHED（上次中断残留），"
                            + "不重复下发指令，补齐分摊并完成结算");
                    log.warn("恢复路径: {} 实收已写分摊缺失，补齐后继续", responseId);
                    break;
                }
                // GAP 终态重跑 或 无实收的 DISPATCHED 残留接管：删除旧行重新认领；
                // committedState 为 null 说明持有方事务未提交（状态不可见），短暂自旋等待。
                if (++claimAttempts >= CLAIM_ATTEMPTS) {
                    if (guardHolding) {
                        idempotencyGuard.release(guardKey);
                    }
                    throw new TaskInProgressException(responseId);
                }
                if (committedState != null) {
                    repo.deleteTask(responseId);
                } else {
                    sleepQuietly(CLAIM_RETRY_INTERVAL_MS);
                }
            }
        }

        try {
            executeClosedLoop(responseId, path, declaredKwh, targetKw, nowMs, recovery, result, trace);
        } catch (RuntimeException e) {
            // 数据库事务回滚后，进程内运行态必须同步回退（详见方法注释）
            if (guardHolding && !guardSyncRegistered) {
                idempotencyGuard.release(guardKey);
            }
            compensateMemoryState(responseId, e);
            throw e;
        }
        if (guardHolding) {
            if ("SETTLED".equals(repo.taskState(responseId))) {
                // 缓存写入挂到事务提交后（第 4 轮复审修复）：提交失败只释放在途标记，
                // 绝不出现「缓存成功、数据库回滚」的虚假成功结果
                registerAfterCommitCacheWrite(guardKey, result);
                guardSyncRegistered = true;
            } else {
                // GAP（不可行）：不缓存成功结果，立即释放在途标记，保留同键重跑语义
                idempotencyGuard.release(guardKey);
            }
        }
        return result;
    }

    /**
     * 结算后争议更正的独立入口：对已结算任务按更正计量（第 2 时段实测修正为
     * correctedActualKw）重算结算四量与分摊，生成下一账期版本的全套更正账单。
     * 与 run(path=DISPUTED) 在任务已结算时走同一流程——争议不吞幂等语义。
     *
     * @param correctionRequestId 纠偏请求幂等键（可选，4-64 位）：同一请求重复提交
     *                            返回原版本结果不重复出账；不传则每次视为新请求（自动登记）
     */
    @Transactional
    public DemoRunResult dispute(String responseId, BigDecimal correctedActualKw) {
        return dispute(responseId, correctedActualKw, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DemoRunResult dispute(String responseId, BigDecimal correctedActualKw, String correctionRequestId) {
        if (responseId == null || !RESPONSE_ID_PATTERN.matcher(responseId).matches()) {
            throw new IllegalArgumentException(
                    "responseId 非法（4-50 位字母/数字/下划线/中划线）: " + responseId);
        }
        if (correctedActualKw == null || correctedActualKw.signum() < 0) {
            throw new IllegalArgumentException("更正实测功率必须非负: " + correctedActualKw);
        }
        if (correctionRequestId != null && !CORRECTION_REQUEST_ID_PATTERN.matcher(correctionRequestId).matches()) {
            throw new IllegalArgumentException(
                    "correctionRequestId 非法（4-64 位字母/数字/下划线/中划线）: " + correctionRequestId);
        }
        DemoRunResult result = new DemoRunResult();
        result.setResponseId(responseId);
        result.setPath("DISPUTE");
        List<String> trace = new ArrayList<>();
        applyCorrection(responseId, correctedActualKw.doubleValue(), correctionRequestId, trace, result);
        result.setTrace(trace);
        return result;
    }

    // ---------------- 主流程 ----------------

    private void executeClosedLoop(String responseId, String path, BigDecimal declaredKwh,
                                   BigDecimal targetKw, long nowMs, boolean recovery,
                                   DemoRunResult result, List<String> trace) {
        // ① 模拟遥测 → 接入校验 → 数据入库（基线样本与实测点）
        // 数据为内置模拟源直接构造（非网关真实接入），链路其余环节走真实业务代码
        trace.add("① 遥测接入（内置模拟源）：采集基线样本与响应期实测，校验通过后入库");
        LocalDate responseDay = LocalDate.now();
        List<SamplePoint> samples = buildSamples(responseDay);

        // ② 能力评估 + ③ 资源聚合
        trace.add("②③ 能力评估+资源聚合：逐成员评估有效能力，聚合成可调容量池");
        List<AssessedResource> members = buildMembers(path);
        BigDecimal committablePool = poolCalculator.poolOf(members);
        trace.add("   聚合可承诺容量（折扣0.9后）= " + committablePool + " kW");

        // ④ 响应任务：先可行性校验
        TaskWindow window = new TaskWindow(nowMs / 1000, 3600);   // 构造为 (起点秒, 时长秒)
        trace.add("④ 响应任务：任务量 " + targetKw + " kW，先做可行性校验");

        // ⑤ 指令分解（含容量预占）
        // 恢复路径跳过：上次中断时预占可能仍登记在台账，重复分解会重复预占、
        // 挤占剩余能力导致误报缺口；且指令已在途/终态，无需重新分解下发。
        InstructionDecomposer.DecompositionResult decomp;
        if (recovery) {
            decomp = InstructionDecomposer.DecompositionResult.feasible(Map.of());
            trace.add("⑤ 指令分解：恢复路径跳过（沿用在途指令与既有预占）");
        } else {
            decomp = decomposer.decompose(
                    members, committablePool, targetKw, AssessedResource.Direction.DOWN,
                    window, responseId, reservationLedger);
        }
        result.setFeasible(decomp.isFeasible());
        result.setGapKw(decomp.getGapKw());
        // 任务结果落定：任务行已由入口原子认领（claimTask），此处 UPDATE 终态/缺口
        repo.updateTaskOutcome(responseId,
                decomp.isFeasible() ? "DISPATCHED" : "GAP", decomp.getGapKw());

        if (!decomp.isFeasible()) {
            trace.add("⑤ 指令分解：不可行，缺口 " + decomp.getGapKw() + " kW（降级路径显式报缺口）");
            result.setTrace(trace);
            log.warn("任务不可行: {} 缺口 {}kW", responseId, decomp.getGapKw());
            return;
        }
        if (!recovery) {
            trace.add("⑤ 指令分解：可行，按成员有效能力等比分配，登记容量预占");
        }

        // ⑥ 指令下发 → ⑦ 执行核验（遥测驱动）
        // 恢复路径跳过下发：指令已在内存仓库（在途或终态），重复 send 会被指令级
        // 幂等拦截，但教学回环的执行核验（onAck/onTelemetry）不重复驱动，
        // 以持久化的指令记录为准。
        if (!recovery) {
            dispatchAndVerify(responseId, decomp.getPlan(), trace);
        } else {
            trace.add("⑥⑦ 指令下发与执行核验：恢复路径跳过（沿用既有指令执行结果）");
        }

        // ⑧ 响应量计算（基线 - 实测，正偏差积分，缺失/零值口径）
        MeteringResult metered = computeResponse(responseId, samples, responseDay, trace);
        result.setBaselineKw(BigDecimal.valueOf(BASELINE_KW));
        result.setActualKw(BigDecimal.valueOf(ACTUAL_KW));
        result.setResponseKwh(BigDecimal.valueOf(metered.responseKwh()).setScale(3, RoundingMode.HALF_UP));

        // ⑨ 结算四量（毛额 → 考核扣款 → 净实收），净实收落账并作为可分配金额
        BigDecimal netYuan = settle(responseId, declaredKwh, metered.responseKwh(), trace, result);

        // ⑩ 分摊 + 账单 + 任务完成状态（同事务边界；分摊全量替换保证幂等可补偿）
        Map<String, BigDecimal> allocation = allocateAndBill(responseId, netYuan,
                BILL_VERSION_ORIGINAL, trace, false);
        result.setAllocation(allocation);
        repo.updateTaskState(responseId, "SETTLED");

        // 资金守恒核对：分配侧（SHARE+PLATFORM_CUT）应等于收入侧净实收（SETTLE）
        checkConservation(responseId, netYuan, BILL_VERSION_ORIGINAL, trace);

        // ⑪ 争议路径：计量补到 → 按更正口径重算 → 版本化更正账单（历史版本保留）
        if ("DISPUTED".equals(path)) {
            applyCorrection(responseId, DISPUTED_CORRECTED_KW, null, trace, result);
        }

        // 预占释放：任务正常完成（终态），按任务标识释放本次预占。
        // 原子认领保证同一任务同一时刻只有一个执行实例，按任务标识释放不会误删他任务预占。
        // 取消/失败路径（本编排内不可达，生产由任务撤销入口调用 releaseByTask）；
        // 演示重置由 resetRuntimeState 整体清空；执行异常由 compensateMemoryState 兜底。
        int released = reservationLedger.releaseByTask(responseId);
        trace.add("   预占释放：任务完成，按任务标识释放容量预占 " + released + " 条");

        result.setTrace(trace);
        log.info("闭环完成: {} 路径 {} 净实收 {}元", responseId, path, netYuan);
    }

    /**
     * 入参前置校验：在任何状态变更（含内存容量预占）之前拒绝非法输入。
     * 典型场景：61 字符 responseId 派生的指令编号超出 instruction_id
     * VARCHAR(64) ——旧实现走到落库才 500、事务回滚但预占残留；现直接拒绝。
     */
    private void validateRunInput(String responseId, String path,
                                  BigDecimal declaredKwh, BigDecimal targetKw) {
        if (responseId == null || !RESPONSE_ID_PATTERN.matcher(responseId).matches()) {
            throw new IllegalArgumentException(
                    "responseId 非法（4-50 位字母/数字/下划线/中划线，派生指令编号须落在 "
                            + "instruction_id VARCHAR(64) 内）: " + responseId);
        }
        if (!"NORMAL".equals(path) && !"DEGRADED".equals(path) && !"DISPUTED".equals(path)) {
            throw new IllegalArgumentException("path 仅支持 NORMAL / DEGRADED / DISPUTED: " + path);
        }
        if (declaredKwh == null || declaredKwh.signum() <= 0) {
            throw new IllegalArgumentException("申报响应电量必须为正数: " + declaredKwh);
        }
        if (targetKw == null || targetKw.signum() <= 0) {
            throw new IllegalArgumentException("调度目标功率必须为正数: " + targetKw);
        }
    }

    /**
     * 结算四量：毛额（结算电量×单价）→ 考核扣款 → 平台净实收（毛额−考核，不为负）。
     * 净实收落账 SETTLE、考核扣款落账 PENALTY（同为 V1），分摊以净实收为可分配金额。
     */
    private BigDecimal settle(String responseId, BigDecimal declaredKwh, double actualKwh,
                              List<String> trace, DemoRunResult result) {
        SettlementCalc c = computeSettlement(declaredKwh.doubleValue(), actualKwh);
        result.setPassRatePct(BigDecimal.valueOf(c.passRate).setScale(1, RoundingMode.HALF_UP));
        result.setGrossYuan(c.grossYuan);
        result.setPenaltyYuan(c.penaltyYuan);
        result.setSettleYuan(c.netYuan);
        repo.saveBill(responseId, "PLATFORM", c.netYuan, "SETTLE", BILL_VERSION_ORIGINAL,
                "平台实收净额[" + BILL_VERSION_ORIGINAL + "][毛额 " + c.grossYuan
                        + " − 考核 " + c.penaltyYuan + "]");
        if (c.penaltyYuan.signum() > 0) {
            repo.saveBill(responseId, "PLATFORM", c.penaltyYuan, "PENALTY", BILL_VERSION_ORIGINAL,
                    "偏差考核扣款[" + BILL_VERSION_ORIGINAL + "][合格率 "
                            + BigDecimal.valueOf(c.passRate).setScale(1, RoundingMode.HALF_UP) + "%]");
        }
        trace.add(String.format(
                "⑨ 结算：合格率 %.1f%%，结算电量 %.0f kWh，毛额 %.2f 元，考核扣款 %.2f 元，"
                        + "平台净实收 %.2f 元（考核从应收补贴中扣除，净实收即可分配金额）",
                c.passRate, c.settleKwh, c.grossYuan.doubleValue(),
                c.penaltyYuan.doubleValue(), c.netYuan.doubleValue()));
        return c.netYuan;
    }

    /** 结算四量统一计算：run 结算与争议更正共用同一口径，杜绝两处算法漂移 */
    private SettlementCalc computeSettlement(double declaredKwh, double actualKwh) {
        double passRate = metering.passRate(actualKwh, declaredKwh);
        double penalty = assessor.assess(passRate, declaredKwh, PRICE_YUAN_PER_KWH);
        double settleKwh = assessor.settleKwh(passRate, declaredKwh, actualKwh);
        BigDecimal grossYuan = BigDecimal.valueOf(settleKwh * PRICE_YUAN_PER_KWH)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal penaltyYuan = BigDecimal.valueOf(penalty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netYuan = grossYuan.subtract(penaltyYuan).max(BigDecimal.ZERO);
        return new SettlementCalc(passRate, settleKwh, grossYuan, penaltyYuan, netYuan);
    }

    /**
     * 资金守恒核对（版本口径）：分配侧（SHARE+PLATFORM_CUT）应等于同版本净实收（SETTLE）。
     */
    private void checkConservation(String responseId, BigDecimal netYuan,
                                   String billVersion, List<String> trace) {
        BigDecimal allocSum = repo.allocationSum(responseId, billVersion);
        boolean conserved = allocSum.compareTo(netYuan) == 0;
        trace.add(String.format("⑩ 分摊守恒核对[%s]：分配侧 %.2f 元 = 净实收 %.2f 元 → %s",
                billVersion, allocSum.doubleValue(), netYuan.doubleValue(),
                conserved ? "守恒✓" : "不守恒✗"));
        if (!conserved) {
            throw new IllegalStateException("资金守恒核对失败: " + responseId
                    + " 版本 " + billVersion + " 分配侧 " + allocSum + " != 净实收 " + netYuan);
        }
    }

    // ---------------- 内部步骤 ----------------

    private List<SamplePoint> buildSamples(LocalDate responseDay) {
        // 响应日前 5 个同类型日同时段负荷（教学数据，均值 1000）
        List<SamplePoint> samples = new ArrayList<>();
        double[] loads = {1000, 1000, 1000, 1000, 1000};
        for (int i = 0; i < loads.length; i++) {
            samples.add(new SamplePoint(responseDay.minusDays(i + 1), loads[i]));
        }
        return samples;
    }

    private List<AssessedResource> buildMembers(String path) {
        // 园区 3 类资源：储能 / 空调 / 充电桩（教学容量；置信度、可持续时长、合同有效）
        List<AssessedResource> members = new ArrayList<>();
        members.add(new AssessedResource("ess-001", "node-A", BigDecimal.valueOf(500),
                BigDecimal.valueOf(0.95), 3600, true));
        members.add(new AssessedResource("ac-001", "node-A", BigDecimal.valueOf(400),
                BigDecimal.valueOf(0.90), 3600, true));
        members.add(new AssessedResource("ev-001", "node-A", BigDecimal.valueOf(300),
                BigDecimal.valueOf(0.85), 3600, true));
        if ("DEGRADED".equals(path)) {
            // 降级路径：充电桩掉线（有效能力归零，从成员中剔除）
            members.removeIf(m -> "ev-001".equals(m.getResourceId()));
        }
        return members;
    }

    private void dispatchAndVerify(String responseId, Map<String, BigDecimal> plan, List<String> trace) {
        int i = 0;
        for (Map.Entry<String, BigDecimal> e : plan.entrySet()) {
            String instructionId = responseId + "-ins-" + (++i);
            DispatchInstruction instruction = new DispatchInstruction(
                    instructionId, e.getKey(), e.getValue(),
                    "activePower", e.getValue());
            instructionService.send(instruction);
            repo.saveInstruction(instructionId, responseId, e.getKey(), e.getValue(),
                    "SENT", System.currentTimeMillis(), null);
            trace.add("⑥ 指令下发：" + instructionId + " → " + e.getKey() + " " + e.getValue() + " kW");

            // 模拟设备回执与遥测达标（本地模拟设备，教学回环）：
            // 遥测点携带严格递增的模拟时间戳（间隔 5s = 一个采样周期），
            // 与"按采样时间去重 + 时间单调性校验"的真实链路语义一致
            LocalDateTime sampleBase = LocalDateTime.now();
            instructionService.onAck(instructionId);
            instructionService.onActStarted(instructionId);
            instructionService.onTelemetry(instructionId, e.getValue(), sampleBase.plusSeconds(5));
            instructionService.onTelemetry(instructionId, e.getValue(), sampleBase.plusSeconds(10));
            boolean ok = instructionRepository.require(instructionId).getState().name().equals("COMPLETED");
            repo.updateInstructionState(instructionId, ok ? "COMPLETED" : "REVIEW",
                    ok ? System.currentTimeMillis() : null);
            trace.add("⑦ 执行核验：" + instructionId + " " + (ok ? "遥测连续稳定达标 COMPLETED" : "转核查 REVIEW"));
        }
    }

    private MeteringResult computeResponse(String responseId, List<SamplePoint> samples,
                                           LocalDate responseDay, List<String> trace) {
        double baseline = baselineCalculator.calculate(samples, responseDay);
        Double[] baselineArr = new Double[POINTS];
        Double[] actualArr = new Double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            baselineArr[i] = baseline;
            actualArr[i] = ACTUAL_KW;
            repo.saveBaselinePoint(responseId, i, RULE_VERSION,
                    BigDecimal.valueOf(baseline).setScale(3, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(ACTUAL_KW).setScale(3, RoundingMode.HALF_UP));
        }
        MeteringResult metered = metering.meterWithGapCheck(baselineArr, actualArr);
        trace.add(String.format("⑧ 响应量：基线 %.0f kW、实测 %.0f kW × %d 时段，正偏差积分 = %.0f kWh（缺失 %d 点）",
                baseline, ACTUAL_KW, POINTS, metered.responseKwh(), metered.missingPoints()));
        return metered;
    }

    /**
     * 分摊 + 账单：保底 50% 按申报容量占比，分成部分按实际贡献（教学演示：3 用户）；
     * 可分配金额 = 净实收（考核扣款不进入分配）。
     * 恢复幂等口径：V1 分摊类账单（SHARE / PLATFORM_CUT）同版本先删后写——全量替换，
     * 既可补齐"实收已写、分摊未写"的中断残留，也可修正半成品分摊，且金额不重复；
     * 实收（SETTLE）不受影响（MERGE 幂等键原地覆盖同值）；
     * 争议更正写新版本：auditInsertOnly=true，一律 INSERT-only 落库（新版本无旧行可覆盖，
     * 主键冲突即抛错——审计账单禁止 MERGE 原地覆盖，历史版本不可变）。
     */
    private Map<String, BigDecimal> allocateAndBill(String responseId, BigDecimal netYuan,
                                                    String billVersion, List<String> trace,
                                                    boolean auditInsertOnly) {
        repo.deleteAllocationBills(responseId, billVersion);

        BigDecimal guaranteed = netYuan.multiply(BigDecimal.valueOf(0.5))
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> declaredKw = new LinkedHashMap<>();
        declaredKw.put("user-storage", BigDecimal.valueOf(500));
        declaredKw.put("user-ac", BigDecimal.valueOf(400));
        declaredKw.put("user-ev", BigDecimal.valueOf(300));
        Map<String, BigDecimal> actualKwh = new LinkedHashMap<>();
        actualKwh.put("user-storage", BigDecimal.valueOf(250));
        actualKwh.put("user-ac", BigDecimal.valueOf(200));
        actualKwh.put("user-ev", BigDecimal.valueOf(150));

        Map<String, BigDecimal> allocation = allocator.allocate(netYuan, guaranteed, declaredKw, actualKwh);
        BigDecimal platformCut = netYuan.subtract(guaranteed)
                .multiply(BigDecimal.valueOf(PLATFORM_CUT_RATE)).setScale(2, RoundingMode.HALF_UP);
        if (auditInsertOnly) {
            repo.insertBill(responseId, "PLATFORM", platformCut, "PLATFORM_CUT", billVersion,
                    "平台服务费10%[" + billVersion + "]");
            allocation.forEach((user, amt) ->
                    repo.insertBill(responseId, user, amt, "SHARE", billVersion, "保底+分成[" + billVersion + "]"));
        } else {
            repo.saveBill(responseId, "PLATFORM", platformCut, "PLATFORM_CUT", billVersion,
                    "平台服务费10%[" + billVersion + "]");
            allocation.forEach((user, amt) ->
                    repo.saveBill(responseId, user, amt, "SHARE", billVersion, "保底+分成[" + billVersion + "]"));
        }
        trace.add("   分摊明细[" + billVersion + "]：可分配金额（净实收）" + netYuan + " 元 → "
                + allocation + "，平台服务费 " + platformCut + " 元");
        return allocation;
    }

    /**
     * 争议更正（结算后）：按更正计量重算结算四量，生成下一版本全套更正账单。
     *
     * 教学模拟：更正作用于第 2 时段（point_index=1）实测，默认补到 400→380 kW，
     * 更正响应量 = 600 + (400−380)×0.25 = 605 kWh：
     *   - 申报 600：合格率 100.8% ≥ 100% 仍按申报封顶 → 金额与 V1 一致，差额 0
     *    （默认案例因封顶暴露不出差额问题，重算口径仍留痕）；
     *   - 申报 650（非封顶场景）：605 < 650 → 净实收 1210 元，较基期 V1（1200 元）
     *    差额 +10 元，必须传导为 V2 服务费与分摊的全量重算。
     * 更正记录五件套：SETTLE（更正净实收）/ PENALTY（更正考核，如有）/
     * PLATFORM_CUT + SHARE（分配侧重算）/ CORRECTION（冲正差额，可正可负）。
     * 原始基线点与全部历史版本账单保留不改；每轮更正独立版本留档（V2、V3…）。
     *
     * 并发口径（第 3 轮复核修复）：请求幂等键留档 → FOR UPDATE 锁任务行串行化版本分配 →
     * 先占 (response_id, correction_request_id) 唯一键再 INSERT-only 写账单。
     * 同键重复提交（含并发）返回已留档版本的原结果，绝不重复出账。
     */
    private void applyCorrection(String responseId, double correctedActualKw,
                                 String correctionRequestId,
                                 List<String> trace, DemoRunResult result) {
        String state = repo.taskState(responseId);
        if (!"SETTLED".equals(state)) {
            throw new IllegalArgumentException(
                    "争议更正要求任务已结算（SETTLED）: " + responseId + " 当前状态 " + state);
        }
        // 申报口径以任务落库值为准（单一事实源），不信任调用方重复传参
        BigDecimal declaredKwh = repo.taskDeclaredKwh(responseId);
        double correctedKwh = correctedResponseKwh(correctedActualKw);
        SettlementCalc c = computeSettlement(declaredKwh.doubleValue(), correctedKwh);

        // ① 纠偏请求幂等：同键重复提交直接返回已留档版本的原结果
        if (correctionRequestId != null) {
            ResponseRepository.CorrectionRecord prior = repo.findCorrection(responseId, correctionRequestId);
            if (prior != null) {
                rebuildCorrectionReplay(responseId, prior, trace, result);
                return;
            }
        }

        // ② 版本原子分配：锁定任务行，同一任务的并发更正在此串行
        repo.lockTaskRow(responseId);
        String baseVersion = repo.latestBillVersion(responseId, "PLATFORM", "SETTLE");
        String newVersion = nextVersion(baseVersion);
        BigDecimal previousYuan = repo.settleAmount(responseId);   // 基期（当前最新版本）净实收
        BigDecimal diffYuan = c.netYuan.subtract(previousYuan);

        // ③ 先占纠偏请求唯一键，再写审计账单（INSERT-only）——并发同键只有一个完成出账
        String requestId = correctionRequestId != null
                ? correctionRequestId : "auto-" + UUID.randomUUID();
        try {
            repo.saveCorrectionRecord(responseId, requestId, newVersion,
                    BigDecimal.valueOf(correctedActualKw), diffYuan);
        } catch (DuplicateKeyException e) {
            // 并发同键：对方请求已留档出账，本请求返回对方原结果（本事务无业务写入，提交无副作用）
            ResponseRepository.CorrectionRecord prior = repo.findCorrection(responseId, requestId);
            if (prior != null) {
                rebuildCorrectionReplay(responseId, prior, trace, result);
                return;
            }
            throw new IllegalStateException("纠偏请求登记冲突且无法读取原记录: " + requestId, e);
        }

        // 收入侧更正记录：更正后净实收 + 考核扣款（如有）+ 冲正差额（一律 INSERT-only）
        repo.insertBill(responseId, "PLATFORM", c.netYuan, "SETTLE", newVersion,
                "更正后平台实收净额[" + newVersion + "][毛额 " + c.grossYuan
                        + " − 考核 " + c.penaltyYuan + "]");
        if (c.penaltyYuan.signum() > 0) {
            repo.insertBill(responseId, "PLATFORM", c.penaltyYuan, "PENALTY", newVersion,
                    "更正考核扣款[" + newVersion + "]");
        }
        repo.insertBill(responseId, "PLATFORM", diffYuan, "CORRECTION", newVersion,
                "计量补到争议更正[" + newVersion + "，基期" + baseVersion + " 实收 " + previousYuan
                        + " 元，更正后 " + c.netYuan + " 元]");
        trace.add(String.format(
                "⑪ 争议更正[%s]：计量补到（第2时段实测 %.0f→%.0f kW），更正响应量 %.0f kWh，"
                        + "更正净实收 %.2f 元，与基期[%s]差额 %.2f 元 → 收入/服务费/分摊全套版本化更正，历史版本保留",
                newVersion, ACTUAL_KW, correctedActualKw, correctedKwh,
                c.netYuan.doubleValue(), baseVersion, diffYuan.doubleValue()));

        // 分配侧更正记录：服务费 + 用户分摊按更正净实收全量重算（新版本 INSERT-only，不动旧版本）
        Map<String, BigDecimal> allocation = allocateAndBill(responseId, c.netYuan, newVersion, trace, true);
        checkConservation(responseId, c.netYuan, newVersion, trace);

        result.setCorrectionVersion(newVersion);
        result.setCorrectedResponseKwh(BigDecimal.valueOf(correctedKwh).setScale(3, RoundingMode.HALF_UP));
        result.setCorrectedSettleYuan(c.netYuan);
        result.setCorrectionDiffYuan(diffYuan);
        result.setAllocation(allocation);
        log.info("争议更正完成: {} 请求 {} 版本 {} 更正净实收 {} 差额 {}",
                responseId, requestId, newVersion, c.netYuan, diffYuan);
    }

    /** 纠偏请求幂等重放：从留档记录还原原版本结果（金额取自账单表，不重新计算） */
    private void rebuildCorrectionReplay(String responseId, ResponseRepository.CorrectionRecord prior,
                                         List<String> trace, DemoRunResult result) {
        String version = prior.getBillVersion();
        BigDecimal correctedKwh = BigDecimal.valueOf(
                        correctedResponseKwh(prior.getCorrectedActualKw().doubleValue()))
                .setScale(3, RoundingMode.HALF_UP);
        result.setCorrectionVersion(version);
        result.setCorrectedResponseKwh(correctedKwh);
        result.setCorrectedSettleYuan(repo.settleAmountOfVersion(responseId, version));
        result.setCorrectionDiffYuan(prior.getDiffYuan());
        result.setAllocation(repo.allocationOfVersion(responseId, version));
        result.setIdempotentReplay(true);
        trace.add("纠偏请求幂等重放：correctionRequestId 已留档，返回版本 " + version
                + " 原结果（未重复出账，历史版本不变）");
        log.info("争议更正幂等重放: {} 版本 {}", responseId, version);
    }

    /** 更正口径响应量：第 2 时段（point_index=1）实测修正为 correctedActualKw，只计正偏差（与核定口径一致） */
    private double correctedResponseKwh(double correctedActualKw) {
        double kwh = 0;
        for (int i = 0; i < POINTS; i++) {
            double actual = (i == 1) ? correctedActualKw : ACTUAL_KW;
            double delta = BASELINE_KW - actual;
            if (delta > 0) {
                kwh += delta * INTERVAL_HOURS;
            }
        }
        return kwh;
    }

    /** 下一账期版本号：V1 → V2 → V3（解析数字递增；异常版本回退 V2） */
    private static String nextVersion(String current) {
        if (current == null || current.length() < 2 || current.charAt(0) != 'V') {
            return "V2";
        }
        try {
            return "V" + (Integer.parseInt(current.substring(1)) + 1);
        } catch (NumberFormatException e) {
            return "V2";
        }
    }

    /**
     * 幂等重放结果重建（第 5 轮复审修复）：此前重放仅返回默认字段 + 幂等标记，调用方
     * 拿不到既有结算金额。现从数据库按最新账期版本重建完整结果——SETTLE 净实收 /
     * PENALTY 考核 / 分摊 / 申报电量 / 基线与实测口径；毛额按「毛额 = 净实收 + 考核」
     * 口径还原（与守恒核对同一恒等式），响应量与合格率复用结算同一公式计算。
     * Redis 结果缓存命中路径不受影响（缓存本就携带完整结果序列）。
     */
    private void rebuildSettledReplay(String responseId, List<String> trace, DemoRunResult result) {
        String version = repo.latestBillVersion(responseId, "PLATFORM", "SETTLE");
        BigDecimal settleYuan = version == null ? BigDecimal.ZERO
                : repo.settleAmountOfVersion(responseId, version);
        BigDecimal penaltyYuan = version == null ? BigDecimal.ZERO
                : repo.penaltyAmountOfVersion(responseId, version);
        BigDecimal grossYuan = settleYuan.add(penaltyYuan);
        BigDecimal declaredKwh = repo.taskDeclaredKwh(responseId);
        BigDecimal gapKw = repo.taskGapKw(responseId);
        double[] baselinePoint = repo.firstBaselinePoint(responseId);
        double responseKwh = grossYuan.doubleValue() / PRICE_YUAN_PER_KWH;

        result.setFeasible(true);
        result.setGapKw(gapKw == null ? BigDecimal.ZERO : gapKw);
        if (baselinePoint != null) {
            result.setBaselineKw(BigDecimal.valueOf(baselinePoint[0]));
            result.setActualKw(BigDecimal.valueOf(baselinePoint[1]));
        }
        result.setResponseKwh(BigDecimal.valueOf(responseKwh).setScale(3, RoundingMode.HALF_UP));
        if (declaredKwh != null && declaredKwh.signum() > 0) {
            result.setPassRatePct(BigDecimal.valueOf(
                            metering.passRate(responseKwh, declaredKwh.doubleValue()))
                    .setScale(1, RoundingMode.HALF_UP));
        }
        result.setGrossYuan(grossYuan);
        result.setPenaltyYuan(penaltyYuan);
        result.setSettleYuan(settleYuan);
        result.setAllocation(repo.allocationOfVersion(responseId, version));
        trace.add("幂等拦截：responseId=" + responseId + " 已结算（SETTLED），不重复执行/出账，"
                + "从数据库重建既有结果返回（版本 " + version + "）");
    }

    /** Redis 缓存命中的结果重放：反序列化后标记幂等重放；反序列化失败回退数据库路径 */
    private DemoRunResult replayFromCache(Optional<String> cachedJson, String responseId) {
        if (!cachedJson.isPresent()) {
            return null;
        }
        // 在途标记（tryBegin 写入的 RUNNING）不是结果 JSON：缓存写入失败等 TTL 边缘场景
        // 会把它 GET 出来，视为未命中走数据库路径，避免反序列化异常告警刷屏（第 5 轮复审）
        if ("RUNNING".equals(cachedJson.get())) {
            return null;
        }
        try {
            DemoRunResult replay = objectMapper.readValue(cachedJson.get(), DemoRunResult.class);
            replay.setIdempotentReplay(true);
            replay.setTrace(List.of("幂等拦截（Redis 缓存）：responseId=" + responseId
                    + " 已结算，直接返回既有结果（缓存 TTL 内不落库）"));
            log.warn("幂等拦截(Redis): {} 命中结果缓存，跳过重复执行", responseId);
            return replay;
        } catch (IOException e) {
            log.warn("Redis 幂等缓存反序列化失败，回退数据库路径: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 结果缓存写入挂到数据库事务提交之后（第 4 轮复审修复）：此前在事务方法返回前写缓存，
     * 提交阶段失败会留下「缓存成功、数据库回滚」的虚假成功结果，后续同键请求命中脏缓存。
     * 现通过事务同步注册：afterCommit 写缓存；afterCompletion 仅在回滚时释放在途标记。
     * 非事务上下文（理论不可达，兜底）退化为立即写。
     */
    private void registerAfterCommitCacheWrite(String guardKey, DemoRunResult result) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            completeGuardCache(guardKey, result);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completeGuardCache(guardKey, result);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    idempotencyGuard.release(guardKey);
                }
            }
        });
    }

    /** 执行成功后写结果缓存（仅 Redis 守卫启用时；写失败只告警，正确性由数据库认领兜底） */
    private void completeGuardCache(String guardKey, DemoRunResult result) {
        try {
            idempotencyGuard.complete(guardKey, objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.warn("结果缓存写入失败（不影响业务正确性）: {}", e.getMessage());
        }
    }

    /**
     * 异常补偿：数据库事务回滚后，回退进程内运行态——释放本任务容量预占、
     * 按派生编号前缀清除内存指令镜像（含在途与终态）。
     * 原子认领保证同一任务同一时刻只有一个执行实例，按任务标识释放/清除只影响本实例产物。
     * 数据库回滚救不了内存：内存台账/仓库与库表必须同进同退，
     * 否则残留预占会把后续任务误报成容量缺口（生产形态以发件箱 + 对账核查兜底，
     * 见 InstructionService 注释）。
     */
    private void compensateMemoryState(String responseId, RuntimeException cause) {
        int released = reservationLedger.releaseByTask(responseId);
        int removed = instructionRepository.removeByInstructionIdPrefix(responseId + "-ins-");
        log.error("闭环异常，内存态补偿: {} 释放预占 {} 条，清除内存指令 {} 条，异常: {}",
                responseId, released, removed, cause.getMessage());
    }

    /**
     * 重置演示运行状态：库表数据已由控制器 deleteAll 清理，这里清空内存运行态——
     * 容量预占台账 + 内存指令仓库 + 幂等缓存命名空间（第 4 轮复审修复：不清缓存则
     * 重置后同键请求在 TTL 窗口内命中旧结果、不重新落库）。
     * 清缓存失败不阻断重置（演示自愈通道；守卫实现内部亦会消化故障，残留键由 TTL 兜底）。
     * 修复历史缺陷：重置只清库不清内存，旧预占残留挤占剩余能力，
     * 重置后再运行正常案例被误报缺口（700+ kW）。
     */
    public void resetRuntimeState() {
        reservationLedger.clear();
        instructionRepository.clear();
        try {
            idempotencyGuard.clearNamespace(RUN_GUARD_KEY_PREFIX);
        } catch (Exception e) {
            log.warn("幂等缓存命名空间清空失败（残留键由 TTL 到期失效）: {}", e.getMessage());
        }
        log.info("演示运行状态已重置：容量预占台账 + 内存指令仓库 + 幂等缓存命名空间已清空");
    }

    /**
     * 取消任务的预占释放入口（生产任务撤销/失败时调用）：
     * 按任务标识释放容量预占，返回释放条数。
     */
    public int releaseReservation(String responseId) {
        int released = reservationLedger.releaseByTask(responseId);
        log.info("按任务释放容量预占: {} 释放 {} 条", responseId, released);
        return released;
    }

    /** 认领自旋等待（持有方事务未提交、状态不可见时）；被中断恢复中断标志并继续 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 结算四量中间结果（run 结算与争议更正共用） */
    private static final class SettlementCalc {
        final double passRate;          // 合格率（%）
        final double settleKwh;         // 结算电量（达标按申报封顶，否则按实际）
        final BigDecimal grossYuan;     // 补偿毛额 = 结算电量 × 单价
        final BigDecimal penaltyYuan;   // 考核扣款
        final BigDecimal netYuan;       // 平台净实收 = max(0, 毛额 − 考核扣款)

        private SettlementCalc(double passRate, double settleKwh,
                               BigDecimal grossYuan, BigDecimal penaltyYuan, BigDecimal netYuan) {
            this.passRate = passRate;
            this.settleKwh = settleKwh;
            this.grossYuan = grossYuan;
            this.penaltyYuan = penaltyYuan;
            this.netYuan = netYuan;
        }
    }
}
