package com.openvpp.aggregator.plan;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.market.charge.CapabilitySnapshot;
import com.openvpp.market.charge.CapacityOccupancyLedger;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDeclarationService;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.DeclarationStatus;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.market.charge.SnapshotRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分级计划服务单测（第 54 篇底座，对应验收格"派单可行性"）：
 * 两级分配守恒、缺口阻止确认与下发、零容量、削峰/填谷方向换算、
 * 人工调整产生新版本、重复确认幂等、发送/回执时标记录、数据范围守卫。
 *
 * 业务全景：平台租户 T-9001 发事件；运营商 T-1001（场站 S-11101）、
 * T-2001（场站 S-21101）、T-3001（场站 S-31101）申报并确认。
 */
class AggregatePlanServiceTest {

    private static final LocalDateTime CREATE_AT = LocalDateTime.of(2026, 10, 8, 12, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 8, 13, 30);
    private static final LocalDateTime DECLARE_AT = LocalDateTime.of(2026, 10, 8, 13, 0);
    private static final LocalDateTime PLAN_AT = LocalDateTime.of(2026, 10, 8, 13, 40);
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 10, 8, 14, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 10, 8, 16, 0);
    private static final LocalDateTime SNAPSHOT_AT = LocalDateTime.of(2026, 10, 8, 12, 50);

    private SnapshotRegistry snapshotRegistry;
    private ChargeDrEventService eventService;
    private ChargeDeclarationService declarationService;
    private AggregatePlanRepository repository;
    private AggregatePlanService planService;

    private int requestSeq;

    @BeforeEach
    void setUp() {
        snapshotRegistry = new SnapshotRegistry(Duration.ofMinutes(60));
        CapacityOccupancyLedger ledger = new CapacityOccupancyLedger();
        eventService = new ChargeDrEventService();
        declarationService = new ChargeDeclarationService(eventService, snapshotRegistry, ledger);
        repository = new AggregatePlanRepository();
        planService = new AggregatePlanService(eventService, snapshotRegistry,
                new ConstraintAllocator(), repository);
        requestSeq = 0;
    }

    private static void assertKw(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + "，实际 " + actual);
    }

    private static void assertKw(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + " —— 期望 " + expected + "，实际 " + actual);
    }

    private static DataScope platformScope(String tenantId) {
        return DataScopeResolver.resolve(
                IdentityContext.of("platform-acc", tenantId, RoleType.PLATFORM_ADMIN, Set.of()));
    }

    private static DataScope operatorScope(String tenantId, String... stationIds) {
        return DataScopeResolver.resolve(
                IdentityContext.of("operator-acc", tenantId, RoleType.OPERATOR, Set.of(stationIds)));
    }

    private static DataScope stationScope(String tenantId, String... stationIds) {
        return DataScopeResolver.resolve(
                IdentityContext.of("station-acc", tenantId, RoleType.STATION_OPERATOR, Set.of(stationIds)));
    }

    private void publishEvent(String eventId, DrDirection direction, BigDecimal targetKw, String... stationIds) {
        eventService.create(eventId, "T-9001", direction,
                WINDOW_START, WINDOW_END, targetKw, DEADLINE, CREATE_AT);
        eventService.publish(platformScope("T-9001"), eventId, CREATE_AT.plusMinutes(5));
        eventService.authorizeParticipation(platformScope("T-9001"), eventId,
                List.of(stationIds), "platform-acc", CREATE_AT.plusMinutes(10));
    }

    /** 登记快照（默认：额定 240 / 基线 120 / 削峰可信 100 / 填谷可信 80） */
    private void registerSnapshot(String stationId, String version, String fingerprint) {
        snapshotRegistry.register(new CapabilitySnapshot(stationId, "ev-charge-" + stationId.toLowerCase(),
                new BigDecimal("240"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"),
                version, SNAPSHOT_AT, fingerprint));
    }

    /** 场站申报 + 运营商确认，返回已确认申报单 */
    private ChargeDeclaration declareAndConfirm(String eventId, String stationId, String tenantId,
                                                BigDecimal kw, String version) {
        ChargeDeclaration declaration = declarationService.declare(
                stationScope(tenantId, stationId), eventId, stationId, tenantId,
                kw, version, "REQ-54-" + (++requestSeq), DECLARE_AT);
        declarationService.confirm(operatorScope(tenantId, stationId), declaration.getDeclarationId(),
                DECLARE_AT.plusMinutes(1));
        return declarationService.require(declaration.getDeclarationId());
    }

    /** 默认削峰事件：目标 150，两个运营商各报 100（已确认） */
    private AggregatePlan buildDefaultPlan() {
        publishEvent("EV-5401", DrDirection.PEAK_SHAVE, new BigDecimal("150"), "S-11101", "S-21101");
        registerSnapshot("S-11101", "V1", "fp-A");
        registerSnapshot("S-21101", "V1", "fp-B");
        ChargeDeclaration d1 = declareAndConfirm("EV-5401", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");
        ChargeDeclaration d2 = declareAndConfirm("EV-5401", "S-21101", "T-2001",
                new BigDecimal("100"), "V1");
        return planService.buildPlan(platformScope("T-9001"), "EV-5401",
                List.of(d1, d2), "planner-acc", PLAN_AT);
    }

    @Test
    void 两级分配守恒且削峰换算为绝对目标() {
        AggregatePlan top = buildDefaultPlan();

        // 一级：目标 150 按申报占比 100:100 切 → 75 / 75，缺口 0
        assertEquals(PlanLevel.PLATFORM_TO_OPERATOR, top.getLevel());
        assertKw("150", top.getAdjustKw());
        assertKw("240", top.getBaselineKw());
        assertKw("0", top.getGapKw());
        assertEquals(2, top.getLines().size());
        assertEquals("T-1001", top.getLines().get(0).getSubjectId());
        assertKw("75", top.getLines().get(0).getAdjustKw());
        assertKw("75", top.getLines().get(1).getAdjustKw());
        assertNull(top.getLines().get(0).getTargetPowerKw(), "一级行只有调节量，不混写目标功率");

        // 二级：每个运营商份额 75 → 单桩目标 = 基线 120 − 75 = 45
        List<AggregatePlan> children = planService.childrenOf(top.getPlanId());
        assertEquals(2, children.size());
        AggregatePlan child = children.stream()
                .filter(c -> c.getLines().stream().anyMatch(l -> l.getStationId().equals("S-11101")))
                .findFirst().orElseThrow();
        assertEquals(PlanLevel.OPERATOR_TO_PILE, child.getLevel());
        assertEquals(top.getPlanId(), child.getParentPlanId());
        assertKw("75", child.getAdjustKw());
        assertKw("0", child.getGapKw());
        assertEquals(1, child.getLines().size());
        AllocationLine pile = child.getLines().get(0);
        assertEquals("ev-charge-s-11101", pile.getSubjectId());
        assertEquals("S-11101", pile.getStationId());
        assertKw("75", pile.getAdjustKw());
        assertKw("120", pile.getBaselineKw());
        assertKw("45", pile.getTargetPowerKw(), "削峰：绝对目标 = 基线 − 调节量");
        assertEquals("V1", pile.getSnapshotVersion(), "快照版本随分配行留痕");

        // 逐级守恒：75 + 75 = 150
        BigDecimal sum = children.stream()
                .map(AggregatePlan::getAdjustKw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertKw("150", sum);
    }

    @Test
    void 申报不足时缺口阻止确认与下发() {
        // 目标 300，可用合计 200 → 顶层缺口 100
        publishEvent("EV-5402", DrDirection.PEAK_SHAVE, new BigDecimal("300"), "S-11101", "S-21101");
        registerSnapshot("S-11101", "V1", "fp-A");
        registerSnapshot("S-21101", "V1", "fp-B");
        ChargeDeclaration d1 = declareAndConfirm("EV-5402", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");
        ChargeDeclaration d2 = declareAndConfirm("EV-5402", "S-21101", "T-2001",
                new BigDecimal("100"), "V1");

        AggregatePlan top = planService.buildPlan(platformScope("T-9001"), "EV-5402",
                List.of(d1, d2), "planner-acc", PLAN_AT);
        assertKw("100", top.getGapKw());
        assertFalse(top.isDispatchable());

        // 确认被缺口阻止（运营三选一：拒绝 / 补申报 / 降目标）
        IllegalStateException confirmRejected = assertThrows(IllegalStateException.class,
                () -> planService.confirm(platformScope("T-9001"), top.getPlanId(), "ops-zhang", PLAN_AT));
        assertTrue(confirmRejected.getMessage().contains("缺口"));
        // 下发被阻止：未确认 + 有缺口，双重拦截
        assertThrows(IllegalStateException.class,
                () -> planService.markSent(platformScope("T-9001"), top.getPlanId(), PLAN_AT));
        assertNull(top.getConfirmation());
        assertNull(top.getSentAt());
    }

    @Test
    void 截止前禁止派单且截止时刻本身允许() {
        publishEvent("EV-5403", DrDirection.PEAK_SHAVE, new BigDecimal("100"), "S-11101");
        registerSnapshot("S-11101", "V1", "fp-A");
        ChargeDeclaration d1 = declareAndConfirm("EV-5403", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");

        // 截止前：申报窗口未关，禁止派单
        IllegalStateException tooEarly = assertThrows(IllegalStateException.class,
                () -> planService.buildPlan(platformScope("T-9001"), "EV-5403",
                        List.of(d1), "planner-acc", DECLARE_AT));
        assertTrue(tooEarly.getMessage().contains("申报未截止"));

        // 截止时刻本身：申报已关（含时刻），允许派单
        AggregatePlan top = planService.buildPlan(platformScope("T-9001"), "EV-5403",
                List.of(d1), "planner-acc", DEADLINE);
        assertKw("100", top.getAdjustKw());
        assertKw("0", top.getGapKw());
    }

    @Test
    void 未发布与已结束事件禁止派单() {
        publishEvent("EV-5404", DrDirection.PEAK_SHAVE, new BigDecimal("100"), "S-11101");
        registerSnapshot("S-11101", "V1", "fp-A");
        ChargeDeclaration d1 = declareAndConfirm("EV-5404", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");

        // CREATED（未发布）：另起一个事件不发布
        eventService.create("EV-5404X", "T-9001", DrDirection.PEAK_SHAVE,
                WINDOW_START, WINDOW_END, new BigDecimal("100"), DEADLINE, CREATE_AT);
        assertThrows(IllegalStateException.class, () -> planService.buildPlan(platformScope("T-9001"),
                "EV-5404X", List.of(d1), "planner-acc", PLAN_AT));

        // ENDED：结束后禁止派单
        eventService.end(platformScope("T-9001"), "EV-5404", PLAN_AT);
        assertThrows(IllegalStateException.class, () -> planService.buildPlan(platformScope("T-9001"),
                "EV-5404", List.of(d1), "planner-acc", PLAN_AT));
    }

    @Test
    void 零容量桩削峰分得零且整体缺口阻止() {
        // S-31101 基线 0：削峰余量 0 → 有效容量 0（申报 100 也不可用）
        publishEvent("EV-5405", DrDirection.PEAK_SHAVE, new BigDecimal("150"), "S-31101", "S-11101");
        snapshotRegistry.register(new CapabilitySnapshot("S-31101", "ev-charge-s-31101",
                new BigDecimal("240"), new BigDecimal("0"),
                new BigDecimal("100"), new BigDecimal("80"),
                "V1", SNAPSHOT_AT, "fp-C"));
        registerSnapshot("S-11101", "V1", "fp-A");
        ChargeDeclaration d1 = declareAndConfirm("EV-5405", "S-31101", "T-3001",
                new BigDecimal("100"), "V1");
        ChargeDeclaration d2 = declareAndConfirm("EV-5405", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");

        AggregatePlan top = planService.buildPlan(platformScope("T-9001"), "EV-5405",
                List.of(d1, d2), "planner-acc", PLAN_AT);
        // 可用合计 100 < 150：T-1001 取满 100，T-3001 分得 0 且留痕在册
        assertKw("50", top.getGapKw());
        assertEquals(2, top.getLines().size());
        AllocationLine zeroLine = top.getLines().stream()
                .filter(l -> l.getSubjectId().equals("T-3001")).findFirst().orElseThrow();
        assertKw("0", zeroLine.getAdjustKw(), "零容量运营商分得零");
        // 缺口阻止确认
        assertThrows(IllegalStateException.class,
                () -> planService.confirm(platformScope("T-9001"), top.getPlanId(), "ops-zhang", PLAN_AT));
    }

    @Test
    void 填谷方向换算与额定边界被裁份额由其余主体吸收() {
        // 目标 80：S-11101 填谷可信 80、余量 120 → 有效 80；
        // S-21101 额定 150、基线 120 → 余量 30，申报 80 被边界裁到 30
        publishEvent("EV-5406", DrDirection.VALLEY_FILL, new BigDecimal("80"), "S-11101", "S-21101");
        registerSnapshot("S-11101", "V1", "fp-A");
        snapshotRegistry.register(new CapabilitySnapshot("S-21101", "ev-charge-s-21101",
                new BigDecimal("150"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"),
                "V1", SNAPSHOT_AT, "fp-B"));
        ChargeDeclaration d1 = declareAndConfirm("EV-5406", "S-11101", "T-1001",
                new BigDecimal("80"), "V1");
        ChargeDeclaration d2 = declareAndConfirm("EV-5406", "S-21101", "T-2001",
                new BigDecimal("80"), "V1");

        AggregatePlan top = planService.buildPlan(platformScope("T-9001"), "EV-5406",
                List.of(d1, d2), "planner-acc", PLAN_AT);
        // 占比 40/40，但 T-2001 份额 40 越其有效上限 30 被裁到 30，
        // 被裁的 10 kW 按序回流到仍有余量的 T-1001 → 50 / 30
        assertKw("0", top.getGapKw(), "总有效容量 110 ≥ 80，顶层无缺口");
        assertKw("50", top.getLines().stream()
                .filter(l -> l.getSubjectId().equals("T-1001")).findFirst().orElseThrow().getAdjustKw());
        assertKw("30", top.getLines().stream()
                .filter(l -> l.getSubjectId().equals("T-2001")).findFirst().orElseThrow().getAdjustKw());

        List<AggregatePlan> children = planService.childrenOf(top.getPlanId());
        assertEquals(2, children.size());
        AggregatePlan child1001 = children.stream()
                .filter(c -> c.getLines().stream().anyMatch(l -> l.getStationId().equals("S-11101")))
                .findFirst().orElseThrow();
        AggregatePlan child2001 = children.stream()
                .filter(c -> c.getLines().stream().anyMatch(l -> l.getStationId().equals("S-21101")))
                .findFirst().orElseThrow();

        // T-1001：份额 50 → 目标 = 120 + 50 = 170（填谷抬升，未越额定 240）
        assertKw("50", child1001.getAdjustKw());
        assertKw("0", child1001.getGapKw());
        assertKw("170", child1001.getLines().get(0).getTargetPowerKw(), "填谷：绝对目标 = 基线 + 调节量");
        // T-2001：份额 30 → 目标 = 120 + 30 = 150 = 额定（填谷至额为界）
        assertKw("30", child2001.getAdjustKw());
        assertKw("0", child2001.getGapKw());
        assertKw("150", child2001.getLines().get(0).getTargetPowerKw(), "填谷至额定为止，不越边界");

        // 整树确认：当前全链路无缺口，可确认
        assertDoesNotThrow(() -> planService.confirm(
                platformScope("T-9001"), top.getPlanId(), "ops-zhang", PLAN_AT));
    }

    @Test
    void 子计划修订出缺口后整树确认被拒绝() {
        AggregatePlan top = buildDefaultPlan();
        assertKw("0", top.getGapKw());

        // 人工修订 T-2001 子计划：份额压到 60（行合计 60 + 缺口 15 = 75），制造子计划缺口
        AggregatePlan child2001 = planService.childrenOf(top.getPlanId()).stream()
                .filter(c -> c.getLines().stream().anyMatch(l -> l.getStationId().equals("S-21101")))
                .findFirst().orElseThrow();
        List<AllocationLine> reduced = List.of(AllocationLine.pileLine("ev-charge-s-21101", "S-21101",
                new BigDecimal("60"), new BigDecimal("120"), new BigDecimal("60"), "V1"));
        planService.revisePlan(platformScope("T-9001"), child2001.getPlanId(),
                new BigDecimal("75"), new BigDecimal("120"), new BigDecimal("15"), reduced,
                "ops-li", PLAN_AT.plusMinutes(5));
        assertKw("15", planService.requireLatest(child2001.getPlanId()).getGapKw());

        // 顶层无缺口但子计划（最新版本）有缺口 → 整树确认被拒绝
        IllegalStateException treeRejected = assertThrows(IllegalStateException.class,
                () -> planService.confirm(platformScope("T-9001"), top.getPlanId(), "ops-zhang", PLAN_AT));
        assertTrue(treeRejected.getMessage().contains("子计划"));
    }

    @Test
    void 未确认申报与撤回申报不参与分配() {
        publishEvent("EV-5407", DrDirection.PEAK_SHAVE, new BigDecimal("100"), "S-11101", "S-21101");
        registerSnapshot("S-11101", "V1", "fp-A");
        registerSnapshot("S-21101", "V1", "fp-B");
        // S-11101 已确认 100；S-21101 一单撤回、一单只提交未确认
        ChargeDeclaration confirmed = declareAndConfirm("EV-5407", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");
        ChargeDeclaration withdrawn = declarationService.declare(
                stationScope("T-2001", "S-21101"), "EV-5407", "S-21101", "T-2001",
                new BigDecimal("100"), "V1", "REQ-54-W", DECLARE_AT);
        declarationService.withdraw(operatorScope("T-2001", "S-21101"),
                withdrawn.getDeclarationId(), DECLARE_AT.plusMinutes(1));
        ChargeDeclaration neverConfirmed = declarationService.declare(
                stationScope("T-2001", "S-21101"), "EV-5407", "S-21101", "T-2001",
                new BigDecimal("100"), "V1", "REQ-54-N", DECLARE_AT);

        AggregatePlan top = planService.buildPlan(platformScope("T-9001"), "EV-5407",
                List.of(confirmed, withdrawn, neverConfirmed), "planner-acc", PLAN_AT);
        // 只有已确认申报参与：T-2001 两单（撤回 + 未确认）被过滤
        assertEquals(1, top.getLines().size());
        assertEquals("T-1001", top.getLines().get(0).getSubjectId());
        assertKw("0", top.getGapKw(), "已确认 100 恰好覆盖目标 100");
        assertEquals(DeclarationStatus.WITHDRAWN, declarationService.require(withdrawn.getDeclarationId()).getStatus());
    }

    @Test
    void 人工调整产生新版本且旧版本证据保留() {
        AggregatePlan top = buildDefaultPlan();
        LocalDateTime confirmAt = PLAN_AT.plusMinutes(1);
        LocalDateTime sentAt = PLAN_AT.plusMinutes(2);
        LocalDateTime ackAt = PLAN_AT.plusMinutes(3);
        planService.confirm(platformScope("T-9001"), top.getPlanId(), "ops-zhang", confirmAt);
        planService.markSent(platformScope("T-9001"), top.getPlanId(), sentAt);
        planService.recordReceipt(platformScope("T-9001"), top.getPlanId(), ackAt);

        // 降目标路径：150 → 140，份额重切 70/70
        List<AllocationLine> newLines = List.of(
                AllocationLine.operatorLine("T-1001", new BigDecimal("70"), new BigDecimal("120")),
                AllocationLine.operatorLine("T-2001", new BigDecimal("70"), new BigDecimal("120")));
        AggregatePlan revised = planService.revisePlan(platformScope("T-9001"), top.getPlanId(),
                new BigDecimal("140"), new BigDecimal("240"), BigDecimal.ZERO, newLines,
                "ops-li", PLAN_AT.plusMinutes(10));

        assertEquals(2, revised.getVersion());
        assertKw("140", revised.getAdjustKw());
        assertNull(revised.getConfirmation(), "新版本须重新确认");
        assertNull(revised.getSentAt(), "新版本证据列重新置空");

        // 旧版本证据原样保留（修订不覆盖已发送指令的证据）
        AggregatePlan v1 = planService.require(top.getPlanId(), 1);
        assertEquals(1, v1.getVersion());
        assertNotNull(v1.getConfirmation());
        assertEquals("ops-zhang", v1.getConfirmation().getConfirmer());
        assertEquals(confirmAt, v1.getConfirmation().getConfirmedAt());
        assertEquals(sentAt, v1.getSentAt());
        assertEquals(ackAt, v1.getAckedAt());
        assertEquals(2, planService.versionsOf(top.getPlanId()).size());

        // 新版本可重新走确认
        AggregatePlan reconfirmed = planService.confirm(platformScope("T-9001"),
                revised.getPlanId(), "ops-wang", PLAN_AT.plusMinutes(11));
        assertEquals("ops-wang", reconfirmed.getConfirmation().getConfirmer());
        assertEquals(2, reconfirmed.getConfirmation().getPlanVersion(), "确认记录锚定被确认版本");
    }

    @Test
    void 重复确认幂等首条记录为准() {
        AggregatePlan top = buildDefaultPlan();
        LocalDateTime firstAt = PLAN_AT.plusMinutes(1);
        planService.confirm(platformScope("T-9001"), top.getPlanId(), "ops-zhang", firstAt);
        // 重复确认（不同确认人/不同时刻）不覆盖首条
        AggregatePlan again = planService.confirm(platformScope("T-9001"), top.getPlanId(),
                "ops-li", PLAN_AT.plusMinutes(9));

        assertEquals("ops-zhang", again.getConfirmation().getConfirmer());
        assertEquals(firstAt, again.getConfirmation().getConfirmedAt());
        assertEquals(1, again.getConfirmation().getPlanVersion());
        assertTrue(again.isDispatchable());
    }

    @Test
    void 发送与回执时标记录且幂等不覆盖() {
        AggregatePlan top = buildDefaultPlan();
        LocalDateTime confirmAt = PLAN_AT.plusMinutes(1);
        LocalDateTime sentAt = PLAN_AT.plusMinutes(2);
        LocalDateTime ackAt = PLAN_AT.plusMinutes(5);

        // 未确认禁止下发
        assertThrows(IllegalStateException.class,
                () -> planService.markSent(platformScope("T-9001"), top.getPlanId(), sentAt));

        planService.confirm(platformScope("T-9001"), top.getPlanId(), "ops-zhang", confirmAt);

        // 已确认未发送：禁止记回执
        assertThrows(IllegalStateException.class,
                () -> planService.recordReceipt(platformScope("T-9001"), top.getPlanId(), ackAt));

        planService.markSent(platformScope("T-9001"), top.getPlanId(), sentAt);
        assertEquals(sentAt, top.getSentAt());
        // 重复发送不覆盖首条证据
        planService.markSent(platformScope("T-9001"), top.getPlanId(), PLAN_AT.plusMinutes(3));
        assertEquals(sentAt, top.getSentAt());

        planService.recordReceipt(platformScope("T-9001"), top.getPlanId(), ackAt);
        assertEquals(ackAt, top.getAckedAt());
        // 重复回执不覆盖首条证据
        planService.recordReceipt(platformScope("T-9001"), top.getPlanId(), PLAN_AT.plusMinutes(6));
        assertEquals(ackAt, top.getAckedAt());
    }

    @Test
    void 数据范围守卫拒绝越权操作() {
        AggregatePlan top = buildDefaultPlan();
        // 空范围：禁止退化为全量操作
        assertThrows(IllegalStateException.class, () -> planService.confirm(
                stationScope("T-9001"), top.getPlanId(), "ops-zhang", PLAN_AT));
        // 场站清单形态（运营商）不得平台确认
        assertThrows(IllegalStateException.class, () -> planService.confirm(
                operatorScope("T-9001", "S-11101"), top.getPlanId(), "ops-zhang", PLAN_AT));
        // 其他租户平台范围：租户锚定不符
        assertThrows(IllegalStateException.class, () -> planService.confirm(
                platformScope("T-1001"), top.getPlanId(), "ops-zhang", PLAN_AT));
        // 派单操作同理
        assertThrows(IllegalStateException.class, () -> planService.buildPlan(
                platformScope("T-1001"), "EV-5401", List.of(), "planner-acc", PLAN_AT));
        // 正确范围可用
        assertDoesNotThrow(() -> planService.confirm(
                platformScope("T-9001"), top.getPlanId(), "ops-zhang", PLAN_AT));
    }

    @Test
    void 快照失效的申报被拒绝参与派单() {
        publishEvent("EV-5408", DrDirection.PEAK_SHAVE, new BigDecimal("100"), "S-11101");
        registerSnapshot("S-11101", "V1", "fp-A");
        ChargeDeclaration d1 = declareAndConfirm("EV-5408", "S-11101", "T-1001",
                new BigDecimal("100"), "V1");
        // 会话变化：登记指纹不同的新版本，旧版本快照失效
        snapshotRegistry.register(new CapabilitySnapshot("S-11101", "ev-charge-s-11101",
                new BigDecimal("240"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"),
                "V2", SNAPSHOT_AT.plusMinutes(5), "fp-A2"));

        // 派单时按申报所引快照版本重新校验：V1 已随会话变化失效
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> planService.buildPlan(platformScope("T-9001"), "EV-5408",
                        List.of(d1), "planner-acc", PLAN_AT));
        assertTrue(stale.getMessage().contains("会话已变化"));
    }
}
