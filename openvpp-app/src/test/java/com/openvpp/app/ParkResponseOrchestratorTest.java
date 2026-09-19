package com.openvpp.app;

import com.openvpp.app.orchestration.DemoRunResult;
import com.openvpp.app.orchestration.ParkResponseOrchestrator;
import com.openvpp.app.persistence.ResponseRepository;
import com.openvpp.dispatch.instruction.InstructionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * 园区需求响应贯穿案例集成测试。
 *
 * 场景：首次执行 / 重复执行（幂等）/ 重置后再执行 / 取消后再执行 /
 * 非零考核（考核扣款从毛额扣除、净实收传导分摊）/ 结算中断恢复 /
 * 争议更正（随 run、结算后独立入口、正负差额、第 3 轮留档）/
 * 落库失败回滚后的内存态补偿与再次执行 / 超长 responseId 前置拒绝。
 *
 * 数值断言与 PARK-DEMO.md 手工核算底稿一致：
 * 默认案例（申报 600）考核为 0：毛额 = 净实收 = 1200.00 元；
 * 分摊 user-storage 475.00 / user-ac 380.00 / user-ev 285.00；
 * 平台服务费 60.00；分配侧合计 = 净实收（资金守恒）。
 * 非零考核案例（申报 800、实际 600）：考核 400.00 元，净实收 800.00 元，
 * 保底 400.00、服务费 40.00、用户合计 760.00。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库，不污染默认演示库
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-test-${random.uuid};AUTO_SERVER=TRUE"
})
class ParkResponseOrchestratorTest {

    @Autowired
    private ParkResponseOrchestrator orchestrator;
    @SpyBean
    private ResponseRepository repo;
    @Autowired
    private InstructionRepository instructionRepository;

    private static final BigDecimal DECLARED = new BigDecimal("600");
    private static final BigDecimal TARGET = new BigDecimal("900");

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        orchestrator.resetRuntimeState();
    }

    /** 金额取数辅助（账期版本口径：同一主体多版本账单并存，断言必须定位到版本） */
    private BigDecimal billAmount(String responseId, String subject, String billType, String version) {
        return repo.listBills(responseId).stream()
                .filter(b -> subject.equals(b.get("SUBJECT")) && billType.equals(b.get("BILL_TYPE"))
                        && version.equals(b.get("BILL_VERSION")))
                .map(b -> (BigDecimal) b.get("AMOUNT_YUAN"))
                .findFirst().orElse(null);
    }

    private long billCount(String responseId, String billType) {
        return repo.listBills(responseId).stream()
                .filter(b -> billType.equals(b.get("BILL_TYPE")))
                .count();
    }

    @Test
    void 首次执行正常路径_金额与守恒符合底稿() {
        DemoRunResult r = orchestrator.run("run-001", "NORMAL", DECLARED, TARGET);

        assertTrue(r.isFeasible());
        assertFalse(r.isIdempotentReplay());
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.getSettleYuan()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getPenaltyYuan()));
        assertEquals(0, new BigDecimal("600.000").compareTo(r.getResponseKwh()));

        assertEquals("SETTLED", repo.taskState("run-001"));
        assertEquals(0, new BigDecimal("475.00").compareTo(billAmount("run-001", "user-storage", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("380.00").compareTo(billAmount("run-001", "user-ac", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("285.00").compareTo(billAmount("run-001", "user-ev", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("60.00").compareTo(billAmount("run-001", "PLATFORM", "PLATFORM_CUT", "V1")));
        // 资金守恒：分配侧 = 净实收
        assertEquals(0, repo.settleAmount("run-001").compareTo(repo.allocationSum("run-001", "V1")));
    }

    @Test
    void 重复执行幂等_不重复出账_重放返回完整重建结果() {
        orchestrator.run("run-001", "NORMAL", DECLARED, TARGET);
        DemoRunResult replay = orchestrator.run("run-001", "NORMAL", DECLARED, TARGET);

        assertTrue(replay.isIdempotentReplay());
        // 账单仍是 5 条（SETTLE×1 + PLATFORM_CUT×1 + SHARE×3），金额不变
        List<Map<String, Object>> bills = repo.listBills("run-001");
        assertEquals(5, bills.size());
        // 重放必须携带从数据库重建的完整结算结果（第 5 轮复审修复：此前只返回默认字段）
        assertEquals(0, new BigDecimal("1200.00").compareTo(replay.getSettleYuan()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(replay.getGrossYuan()));
        assertEquals(0, BigDecimal.ZERO.compareTo(replay.getPenaltyYuan()));
        assertEquals(0, new BigDecimal("600.000").compareTo(replay.getResponseKwh()));
        assertEquals(3, replay.getAllocation().size(), "重放还原用户分摊明细");
        assertEquals(0, repo.settleAmount("run-001").compareTo(repo.allocationSum("run-001", "V1")));
    }

    @Test
    void 非零考核_扣款从毛额扣除_净实收传导分摊并守恒() {
        // 申报 800、实际 600：合格率 75%（70%-90% 档，欠额 200kWh × 2 元 × 1.0 = 400 元考核）
        DemoRunResult r = orchestrator.run("pen-800", "NORMAL", new BigDecimal("800"), TARGET);

        assertTrue(r.isFeasible());
        // 结算四量：毛额 1200（结算电量 600×2）→ 考核扣款 400 → 净实收 800
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.getGrossYuan()));
        assertEquals(0, new BigDecimal("400.00").compareTo(r.getPenaltyYuan()));
        assertEquals(0, new BigDecimal("800.00").compareTo(r.getSettleYuan()),
                "考核费用必须从应收补贴中扣除，而不是只进返回字段与日志");
        // 考核扣款入账 PENALTY；净实收入账 SETTLE
        assertEquals(0, new BigDecimal("400.00").compareTo(billAmount("pen-800", "PLATFORM", "PENALTY", "V1")));
        assertEquals(0, new BigDecimal("800.00").compareTo(billAmount("pen-800", "PLATFORM", "SETTLE", "V1")));
        // 分摊以净实收 800 为可分配金额：保底 400、服务费 40、用户合计 760
        assertEquals(0, new BigDecimal("40.00").compareTo(billAmount("pen-800", "PLATFORM", "PLATFORM_CUT", "V1")));
        assertEquals(0, new BigDecimal("316.67").compareTo(billAmount("pen-800", "user-storage", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("253.33").compareTo(billAmount("pen-800", "user-ac", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("190.00").compareTo(billAmount("pen-800", "user-ev", "SHARE", "V1")));
        // 资金核对：分配侧 = 净实收；毛额 = 净实收 + 考核
        assertEquals(0, new BigDecimal("800.00").compareTo(repo.allocationSum("pen-800", "V1")));
        assertEquals(0, new BigDecimal("1200.00").compareTo(
                r.getSettleYuan().add(r.getPenaltyYuan())));
    }

    @Test
    void 重置后再执行_预占与指令仓库同步清理_不报缺口() {
        orchestrator.run("run-001", "NORMAL", DECLARED, TARGET);

        // 重置：清库 + 清内存运行态（修复复核②：旧预占残留误报缺口 710kW）
        repo.deleteAll();
        orchestrator.resetRuntimeState();

        DemoRunResult again = orchestrator.run("run-001", "NORMAL", DECLARED, TARGET);
        assertTrue(again.isFeasible(), "重置后再执行不应报缺口，gapKw=" + again.getGapKw());
        assertEquals(0, BigDecimal.ZERO.compareTo(again.getGapKw()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(again.getSettleYuan()));
        assertEquals(0, repo.settleAmount("run-001").compareTo(repo.allocationSum("run-001", "V1")));
    }

    @Test
    void 取消后再执行_按任务标识释放预占_不报缺口() {
        // 模拟"任务预占已登记但任务被撤销"：先跑一次占据容量，再按任务标识释放
        orchestrator.run("run-cancel", "NORMAL", DECLARED, TARGET);
        int released = orchestrator.releaseReservation("run-cancel");
        // 任务正常完成时已自动释放，此处再释放为 0（验证幂等，不抛错、不负数）
        assertEquals(0, released);

        // 用新 responseId 再执行：取消释放后剩余能力应完整，不报缺口
        repo.deleteAll();
        orchestrator.resetRuntimeState();
        DemoRunResult r = orchestrator.run("run-after-cancel", "NORMAL", DECLARED, TARGET);
        assertTrue(r.isFeasible(), "取消释放后再执行不应报缺口，gapKw=" + r.getGapKw());
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.getSettleYuan()));
    }

    @Test
    void 结算中断恢复_实收已写分摊缺失_重跑补齐且金额不重复() {
        // 构造中断残留：实收已写、分摊未写、任务仍为 DISPATCHED（复核③现场）
        repo.saveTask("run-crash", "evt-run-crash", DECLARED, TARGET,
                System.currentTimeMillis(), System.currentTimeMillis() + 3600_000,
                "DISPATCHED", BigDecimal.ZERO);
        repo.saveBill("run-crash", "PLATFORM", new BigDecimal("1200.00"),
                "SETTLE", "V1", "平台实收净额[V1]");

        DemoRunResult r = orchestrator.run("run-crash", "NORMAL", DECLARED, TARGET);

        // 不应被误判幂等短路；分摊补齐
        assertFalse(r.isIdempotentReplay());
        assertEquals("SETTLED", repo.taskState("run-crash"));
        assertEquals(0, new BigDecimal("475.00").compareTo(billAmount("run-crash", "user-storage", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("380.00").compareTo(billAmount("run-crash", "user-ac", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("285.00").compareTo(billAmount("run-crash", "user-ev", "SHARE", "V1")));
        assertEquals(0, new BigDecimal("60.00").compareTo(billAmount("run-crash", "PLATFORM", "PLATFORM_CUT", "V1")));
        // 金额不重复：实收仍 1200，分配侧合计 = 净实收（而非两倍）
        assertEquals(0, new BigDecimal("1200.00").compareTo(repo.settleAmount("run-crash")));
        assertEquals(0, repo.settleAmount("run-crash").compareTo(repo.allocationSum("run-crash", "V1")));
    }

    @Test
    void 争议路径随run执行_生成版本化更正账单_原始账单保留() {
        DemoRunResult r = orchestrator.run("run-dispute", "DISPUTED", DECLARED, TARGET);

        assertTrue(r.isFeasible());
        // 更正账单存在，memo 携带账期版本号
        BigDecimal correction = billAmount("run-dispute", "PLATFORM", "CORRECTION", "V2");
        assertTrue(correction != null, "争议路径应生成 CORRECTION 更正账单");
        String memo = repo.listBills("run-dispute").stream()
                .filter(b -> "CORRECTION".equals(b.get("BILL_TYPE")))
                .map(b -> (String) b.get("MEMO"))
                .findFirst().orElse("");
        assertTrue(memo.contains("V2"), "更正账单 memo 应携带更正版本号 V2: " + memo);

        // 原始账单（V1）保留：净实收仍 1200.00，V1 分摊不变；
        // 更正响应量 605kWh > 申报 600 仍封顶 → V2 净实收与 V1 一致，差额为 0
        assertEquals(0, new BigDecimal("605.000").compareTo(r.getCorrectedResponseKwh()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(billAmount("run-dispute", "PLATFORM", "SETTLE", "V1")));
        assertEquals(0, new BigDecimal("1200.00").compareTo(billAmount("run-dispute", "PLATFORM", "SETTLE", "V2")));
        assertEquals(0, new BigDecimal("475.00").compareTo(billAmount("run-dispute", "user-storage", "SHARE", "V1")));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(correction),
                "重算口径留痕，金额不变（更正后仍按申报封顶）");
        assertEquals(0, repo.settleAmount("run-dispute").compareTo(repo.allocationSum("run-dispute", "V2")));
    }

    @Test
    void 结算后run争议路径_不被幂等拦截_转入更正() {
        // 复核现场：先正常结算，再对同一任务提交争议路径，曾被"已结算"直接拦截
        orchestrator.run("disp-2", "NORMAL", new BigDecimal("650"), TARGET);

        DemoRunResult r = orchestrator.run("disp-2", "DISPUTED", new BigDecimal("650"), TARGET);

        assertFalse(r.isIdempotentReplay(), "结算后争议是更正请求，不是幂等重放");
        assertEquals("V2", r.getCorrectionVersion());
        // 申报 650 非封顶场景：更正响应量 605 → 净实收 1210，差额 +10 传导到分摊
        assertEquals(0, new BigDecimal("1210.00").compareTo(r.getCorrectedSettleYuan()));
        assertEquals(0, new BigDecimal("10.00").compareTo(r.getCorrectionDiffYuan()));
        assertEquals(0, new BigDecimal("1210.00").compareTo(repo.settleAmount("disp-2")));
        assertEquals(0, repo.settleAmount("disp-2").compareTo(repo.allocationSum("disp-2", "V2")));
        assertEquals(0, new BigDecimal("60.50").compareTo(billAmount("disp-2", "PLATFORM", "PLATFORM_CUT", "V2")));
    }

    @Test
    void 独立更正入口_正负差额传导分摊_第3轮更正独立留档() {
        orchestrator.run("disp-3", "NORMAL", new BigDecimal("650"), TARGET);
        assertEquals(0, new BigDecimal("1200.00").compareTo(repo.settleAmount("disp-3")));

        // 第 1 轮更正（实测 400→380）：正差额 +10 → V2 全套更正，分摊按 1210 重算
        DemoRunResult c1 = orchestrator.dispute("disp-3", new BigDecimal("380"));
        assertEquals("V2", c1.getCorrectionVersion());
        assertEquals(0, new BigDecimal("10.00").compareTo(c1.getCorrectionDiffYuan()));
        assertEquals(0, new BigDecimal("1210.00").compareTo(billAmount("disp-3", "PLATFORM", "SETTLE", "V2")));
        assertEquals(0, new BigDecimal("60.50").compareTo(billAmount("disp-3", "PLATFORM", "PLATFORM_CUT", "V2")));
        assertEquals(0, new BigDecimal("478.97").compareTo(billAmount("disp-3", "user-storage", "SHARE", "V2")));
        assertEquals(0, repo.settleAmount("disp-3").compareTo(repo.allocationSum("disp-3", "V2")));

        // 第 2 轮更正（实测 400→430）：负差额 −25 → V3，净实收 1185
        DemoRunResult c2 = orchestrator.dispute("disp-3", new BigDecimal("430"));
        assertEquals("V3", c2.getCorrectionVersion());
        assertEquals(0, new BigDecimal("-25.00").compareTo(c2.getCorrectionDiffYuan()));
        assertEquals(0, new BigDecimal("1185.00").compareTo(billAmount("disp-3", "PLATFORM", "SETTLE", "V3")));
        assertEquals(0, new BigDecimal("-25.00").compareTo(billAmount("disp-3", "PLATFORM", "CORRECTION", "V3")));
        assertEquals(0, repo.settleAmount("disp-3").compareTo(repo.allocationSum("disp-3", "V3")));

        // 第 3 轮更正（再回到 380）：正差额 +25 → V4；四个版本账单并存独立留档
        DemoRunResult c3 = orchestrator.dispute("disp-3", new BigDecimal("380"));
        assertEquals("V4", c3.getCorrectionVersion());
        assertEquals(0, new BigDecimal("25.00").compareTo(c3.getCorrectionDiffYuan()));
        assertEquals(4, billCount("disp-3", "SETTLE"), "V1-V4 四版净实收并存");
        assertEquals(3, billCount("disp-3", "CORRECTION"), "三轮更正各留一条冲正记录");
        // 各版本独立守恒：V1 与 V4 同时核对
        assertEquals(0, new BigDecimal("1200.00").compareTo(repo.allocationSum("disp-3", "V1")));
        assertEquals(0, new BigDecimal("1210.00").compareTo(repo.allocationSum("disp-3", "V4")));
    }

    @Test
    void 落库失败事务回滚_内存预占与指令补偿_再次执行不误报缺口() {
        // 复核现场：61 字符 responseId 派生编号超长导致落库 500——现以注入式失败
        // 复现同类路径（下发后落库失败 → 事务回滚），验证内存态补偿闭环
        doThrow(new RuntimeException("模拟指令落库失败"))
                .doCallRealMethod()
                .when(repo).saveInstruction(eq("crash-tx-ins-1"), eq("crash-tx"),
                        any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> orchestrator.run("crash-tx", "NORMAL", DECLARED, TARGET));

        // 数据库事务已回滚（任务无残留）；内存运行态已同步补偿（指令镜像无残留、预占已释放）
        assertNull(repo.taskState("crash-tx"), "事务回滚后任务表不应有残留");
        assertTrue(instructionRepository.find("crash-tx-ins-1").isEmpty(),
                "补偿后内存指令镜像不应残留");

        // 再次执行（同一 responseId）：预占已释放不再误报缺口，闭环完整走通
        DemoRunResult r = orchestrator.run("crash-tx", "NORMAL", DECLARED, TARGET);
        assertTrue(r.isFeasible(), "回滚补偿后再次执行不应误报缺口，gapKw=" + r.getGapKw());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getGapKw()));
        assertEquals("SETTLED", repo.taskState("crash-tx"));
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.getSettleYuan()));
        assertEquals(0, repo.settleAmount("crash-tx").compareTo(repo.allocationSum("crash-tx", "V1")));
    }

    @Test
    void 超长responseId前置拒绝_不产生任何状态() {
        // 复核现场：61 字符任务编号派生指令编号超出 VARCHAR(64)，旧实现落库 500 且预占残留
        String longId = "r".repeat(61);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orchestrator.run(longId, "NORMAL", DECLARED, TARGET));
        assertTrue(ex.getMessage().contains("responseId"), "拒绝原因应指向 responseId 约束");
        assertNull(repo.taskState(longId), "前置拒绝不得产生任何库表状态");
        assertTrue(instructionRepository.find(longId + "-ins-1").isEmpty(),
                "前置拒绝不得产生任何内存状态");
    }

    @Test
    void 降级路径_显式报缺口不出账() {
        DemoRunResult r = orchestrator.run("run-degraded", "DEGRADED", DECLARED, TARGET);

        assertFalse(r.isFeasible());
        assertEquals(0, new BigDecimal("148.5").compareTo(r.getGapKw()));
        assertEquals("GAP", repo.taskState("run-degraded"));
        assertTrue(repo.listBills("run-degraded").isEmpty(), "降级不可行不应出账");
    }
}
