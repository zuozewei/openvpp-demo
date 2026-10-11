package com.openvpp.settlement;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.market.charge.EventLifecycle;
import com.openvpp.resource.org.OperatingEntity;
import com.openvpp.resource.org.OrgRegistryService;
import com.openvpp.resource.org.Station;
import com.openvpp.resource.org.Tenant;
import com.openvpp.settlement.assess.AssessmentConclusion;
import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.AssessmentRule;
import com.openvpp.settlement.assess.AssessmentService;
import com.openvpp.settlement.assess.BaselineFreeze;
import com.openvpp.settlement.assess.BaselineFreezeRegistry;
import com.openvpp.settlement.assess.DirectionalMetering;
import com.openvpp.settlement.assess.FrozenBaselinePoint;
import com.openvpp.settlement.assess.MeteredSample;
import com.openvpp.settlement.charge.AllocationLine;
import com.openvpp.settlement.charge.AllocationParty;
import com.openvpp.settlement.charge.CorrectionRecord;
import com.openvpp.settlement.charge.PaymentRecord;
import com.openvpp.settlement.charge.SettlementOrder;
import com.openvpp.settlement.charge.SettlementService;
import com.openvpp.settlement.charge.SettlementStatus;
import com.openvpp.settlement.charge.SharingRuleSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多级分账全场景单测（第 57 篇底座，3.5.5"结算与更正"验收格）：
 * 比例不合法拒绝 / 金额守恒 / 尾差确定性 / 零净实收不生正向收益 / 重复生成幂等 /
 * 并发确认幂等且旧版本不覆盖 / 更正版本链与差额可复算 / 更正幂等 / 付款登记幂等且仅模拟 /
 * 未确认拒绝付款、已付款拒绝重复登记与更正 / MANUAL_REVIEW 拒绝入结算 / 扣款超毛额拒绝 /
 * 分账主体按归属链解析 / 跨租户与越角色访问拒绝 / 数据范围查询与空范围短路。
 */
class SettlementServiceTest {

    private static final String TENANT = "tenant-openvpp";
    private static final String OTHER_TENANT = "tenant-other";
    private static final String ENTITY = "entity-openvpp-demo";
    private static final String STATION = "cs-station-01";
    private static final String OTHER_STATION = "cs-station-02";
    private static final String RESOURCE = "pile-cs01-001";
    private static final AssessmentRule RULE = AssessmentRule.teachingDefault();
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 11, 12, 0);
    private static final BigDecimal PRICE = new BigDecimal("2.50");

    private final BaselineFreezeRegistry freezeRegistry = new BaselineFreezeRegistry();
    private final AssessmentService assessmentService =
            new AssessmentService(freezeRegistry, new DirectionalMetering());
    private final OrgRegistryService orgRegistry = new OrgRegistryService();
    private final SettlementService settlementService =
            new SettlementService(assessmentService, orgRegistry);

    private final AtomicInteger sequence = new AtomicInteger();

    SettlementServiceTest() {
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT);
        tenant.setTenantName("openvpp 演示租户");
        orgRegistry.registerTenant(tenant);
        OperatingEntity entity = new OperatingEntity();
        entity.setEntityId(ENTITY);
        entity.setTenantId(TENANT);
        entity.setEntityName("openvpp 演示运营主体");
        entity.setEntityKind("充电运营");
        orgRegistry.registerEntity(entity);
        Station station = new Station();
        station.setStationId(STATION);
        station.setTenantId(TENANT);
        station.setEntityId(ENTITY);
        station.setStationName("演示充电场站一");
        orgRegistry.registerStation(station);
        // 第二场站：同属一个运营主体，验证场站级数据范围收窄
        Station other = new Station();
        other.setStationId(OTHER_STATION);
        other.setTenantId(TENANT);
        other.setEntityId(ENTITY);
        other.setStationName("演示充电场站二");
        orgRegistry.registerStation(other);
    }

    // ---------------- 规则快照：比例合法性 ----------------

    @Test
    void 比例总和不为1一律拒绝() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () ->
                SharingRuleSnapshot.teaching(new BigDecimal("0.50"), new BigDecimal("0.30"),
                        new BigDecimal("0.30")));
        assertTrue(e1.getMessage().contains("比例总和必须为 1"), "比例口径: " + e1.getMessage());
        assertThrows(IllegalArgumentException.class, () ->
                SharingRuleSnapshot.teaching(new BigDecimal("0.50"), new BigDecimal("0.50"),
                        new BigDecimal("0.01")), "不足 1 同样拒绝");
        assertThrows(IllegalArgumentException.class, () ->
                SharingRuleSnapshot.teaching(new BigDecimal("0.50"), new BigDecimal("0.30"),
                        new BigDecimal("0.30")), "超过 1 同样拒绝");
    }

    @Test
    void 单方比例越界拒绝() {
        assertThrows(IllegalArgumentException.class, () ->
                SharingRuleSnapshot.teaching(new BigDecimal("-0.10"), new BigDecimal("0.60"),
                        new BigDecimal("0.50")), "负比例拒绝");
        assertThrows(IllegalArgumentException.class, () ->
                SharingRuleSnapshot.teaching(new BigDecimal("1.10"), new BigDecimal("0.00"),
                        new BigDecimal("-0.10")), "超 1 比例拒绝");
    }

    // ---------------- 生成：四量口径与守恒 ----------------

    @Test
    void 生成四量齐备且分账守恒() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        assertEquals(1.0, assessment.getConfirmedResponseKwh(), 1e-9, "夹具核定 1.0 kWh");

        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, new BigDecimal("0.50"), rule(), "REQ-create-1", "platform_admin", BASE.plusMinutes(10));

        assertEquals(new BigDecimal("2.50"), order.getGrossYuan(), "毛额 = 1.0 kWh × 2.50 元/kWh");
        assertEquals(new BigDecimal("0.50"), order.getDeductionYuan());
        assertEquals(new BigDecimal("2.00"), order.getNetYuan(), "净实收 = 毛额 − 考核扣款");
        assertEquals(SettlementStatus.CREATED, order.getStatus());
        assertEquals(1, order.getBillVersion(), "首版本 v1");
        // 评估版本三要素随单留痕
        assertEquals(assessment.getAssessmentId(), order.getAssessmentId());
        assertEquals(RULE.getRuleVersion(), order.getAssessmentRuleVersion());
        assertEquals("BL-v1", order.getBaselineVersion());
        assertEquals(SharingRuleSnapshot.TEACHING_RULE_VERSION, order.getRuleSnapshot().getRuleVersion(),
                "分账规则快照随单保存");
        // 分账守恒：合计恒等于净实收
        BigDecimal lineSum = order.getAllocationLines().stream()
                .map(AllocationLine::getAmountYuan)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(order.getNetYuan(), lineSum, "分账合计必须恒等于净实收");
        // 分账主体按归属链解析（非请求方自填）
        assertEquals(TENANT, orderPartyId(order, AllocationParty.PLATFORM), "平台方 = 组织方租户");
        assertEquals(ENTITY, orderPartyId(order, AllocationParty.OPERATOR), "运营商 = 场站归属运营主体");
        assertEquals(STATION, orderPartyId(order, AllocationParty.STATION), "场站 = 评估场站");
    }

    @Test
    void 分账守恒全区间扫描且同输入同输出() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        // 净实收从 0.01 元到 10.00 元全区间扫描：任意金额下合计恒等于净实收
        for (int cents = 1; cents <= 1000; cents++) {
            BigDecimal price = BigDecimal.valueOf(cents / 100.0);
            SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                    price, null, rule(), "REQ-sweep-" + cents, "platform_admin", BASE.plusMinutes(10));
            BigDecimal lineSum = order.getAllocationLines().stream()
                    .map(AllocationLine::getAmountYuan)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(order.getNetYuan(), lineSum, "净实收 " + order.getNetYuan() + " 分账不守恒");
        }
        // 同输入同输出：同价同规则再算一单，分账金额逐分一致
        SettlementOrder again = settlementService.create(platformScope(), assessment.getAssessmentId(),
                new BigDecimal("3.33"), null, rule(), "REQ-repeat", "platform_admin", BASE.plusMinutes(11));
        SettlementOrder twice = settlementService.create(platformScope(), assessment.getAssessmentId(),
                new BigDecimal("3.33"), null, rule(), "REQ-repeat-2", "platform_admin", BASE.plusMinutes(11));
        for (int i = 0; i < 3; i++) {
            assertEquals(again.getAllocationLines().get(i).getAmountYuan(),
                    twice.getAllocationLines().get(i).getAmountYuan(), "尾差分配必须确定性");
        }
    }

    @Test
    void 尾差归并比例最大方_并列取固定顺序先者() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        // 净实收 3.33（1.0 kWh × 3.33 元/kWh），比例 0.35/0.35/0.30：
        // 单方 DOWN 舍入 1.16 / 1.16 / 0.99 = 3.31，尾差 0.02
        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                new BigDecimal("3.33"), null,
                SharingRuleSnapshot.teaching(new BigDecimal("0.35"), new BigDecimal("0.35"),
                        new BigDecimal("0.30")),
                "REQ-tail", "platform_admin", BASE.plusMinutes(10));
        assertEquals(new BigDecimal("3.33"), order.getNetYuan());
        // 平台/运营商并列最大，固定顺序取先者（平台）吸收尾差 0.02
        assertEquals(new BigDecimal("1.18"), amountOf(order, AllocationParty.PLATFORM), "尾差归并平台线");
        assertEquals(new BigDecimal("1.16"), amountOf(order, AllocationParty.OPERATOR));
        assertEquals(new BigDecimal("0.99"), amountOf(order, AllocationParty.STATION));
        // 最大方非首位时归并最大方：比例 0.20/0.50/0.30，净实收 0.01 → 运营商吸收 0.01
        SettlementOrder tiny = settlementService.create(platformScope(), assessment.getAssessmentId(),
                new BigDecimal("0.01"), null,
                SharingRuleSnapshot.teaching(new BigDecimal("0.20"), new BigDecimal("0.50"),
                        new BigDecimal("0.30")),
                "REQ-tail-tiny", "platform_admin", BASE.plusMinutes(10));
        assertEquals(new BigDecimal("0.00"), amountOf(tiny, AllocationParty.PLATFORM));
        assertEquals(new BigDecimal("0.01"), amountOf(tiny, AllocationParty.OPERATOR), "尾差归并比例最大方");
        assertEquals(new BigDecimal("0.00"), amountOf(tiny, AllocationParty.STATION));
    }

    @Test
    void 零净实收不生成正向收益() {
        // 实际功率 ≥ 基线：核定 0 kWh，结论仍可 CONFIRMED（证据齐备，只是无响应量）
        AssessmentRecord zero = confirmedAssessment(STATION, RESOURCE, 100.0, 100.0);
        assertEquals(0.0, zero.getConfirmedResponseKwh(), 1e-9);
        SettlementOrder order = settlementService.create(platformScope(), zero.getAssessmentId(),
                PRICE, null, rule(), "REQ-zero", "platform_admin", BASE.plusMinutes(10));
        assertEquals(new BigDecimal("0.00"), order.getGrossYuan());
        assertEquals(new BigDecimal("0.00"), order.getNetYuan(), "零净实收");
        for (AllocationLine line : order.getAllocationLines()) {
            assertEquals(new BigDecimal("0.00"), line.getAmountYuan(), "不生成正向收益: " + line.getParty());
        }
    }

    @Test
    void 重复生成幂等_请求标识命中返回原单() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder first = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-idem", "platform_admin", BASE.plusMinutes(10));
        SettlementOrder retry = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-idem", "platform_admin", BASE.plusMinutes(11));
        assertSame(first, retry, "重复生成返回原单");
        assertEquals(1, settlementService.query(platformScope()).size(), "重试不重复出单");
    }

    @Test
    void 人工复核评估拒绝入结算() {
        // 有效样点 2 个（< 3）+ 缺失 8 个 → MANUAL_REVIEW
        String eventId = nextEvent();
        freezeRegistry.freeze(freeze(eventId, STATION, RESOURCE, DrDirection.PEAK_SHAVE, 100.0));
        BaselineFreeze freeze = freezeRegistry.requireFrozen(eventId, STATION, RESOURCE);
        long w = freeze.windowStartMs();
        AssessmentRecord manual = assessmentService.assess(platformScope(), endedEvent(eventId),
                STATION, RESOURCE, List.of(MeteredSample.effective(w, 40),
                        MeteredSample.effective(w + 30_000, 40)), 8, RULE, BASE.plusMinutes(5));
        assertEquals(AssessmentConclusion.MANUAL_REVIEW, manual.getConclusion());

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                settlementService.create(platformScope(), manual.getAssessmentId(), PRICE, null,
                        rule(), "REQ-manual", "platform_admin", BASE.plusMinutes(10)));
        assertTrue(e.getMessage().contains("CONFIRMED"), "仅核定结论入结算: " + e.getMessage());
    }

    @Test
    void 考核扣款超毛额拒绝() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                settlementService.create(platformScope(), assessment.getAssessmentId(), PRICE,
                        new BigDecimal("9.99"), rule(), "REQ-over", "platform_admin", BASE.plusMinutes(10)));
        assertTrue(e.getMessage().contains("不得超过补偿毛额"), "扣款上限守卫: " + e.getMessage());
    }

    @Test
    void 场站未登记归属链断裂拒绝结算() {
        AssessmentRecord orphan = confirmedAssessment("cs-station-99", RESOURCE, 100.0, 40.0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                settlementService.create(platformScope(), orphan.getAssessmentId(), PRICE, null,
                        rule(), "REQ-orphan", "platform_admin", BASE.plusMinutes(10)));
        assertTrue(e.getMessage().contains("未登记场站"), "归属链断裂拒绝: " + e.getMessage());
    }

    @Test
    void 生成越权与空范围拒绝() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        assertThrows(IllegalStateException.class, () -> settlementService.create(
                stationScope(STATION), assessment.getAssessmentId(), PRICE, null,
                rule(), "REQ-station", "station_admin", BASE.plusMinutes(10)), "场站角色禁止生成");
        assertThrows(IllegalStateException.class, () -> settlementService.create(
                emptyScope(), assessment.getAssessmentId(), PRICE, null,
                rule(), "REQ-empty", "platform_admin", BASE.plusMinutes(10)), "空范围禁止退化");
        assertThrows(IllegalStateException.class, () -> settlementService.create(
                otherTenantScope(), assessment.getAssessmentId(), PRICE, null,
                rule(), "REQ-cross", "platform_admin", BASE.plusMinutes(10)), "跨租户生成拒绝");
    }

    // ---------------- 确认：并发幂等，旧版本不覆盖 ----------------

    @Test
    void 并发确认幂等_首条留痕为准() throws Exception {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-confirm", "platform_admin", BASE.plusMinutes(10));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger idempotentHits = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    boolean transitioned = settlementService.confirm(platformScope(),
                            order.getSettlementId(), "platform_admin", BASE.plusMinutes(11));
                    if (transitioned) {
                        confirmed.incrementAndGet();
                    } else {
                        idempotentHits.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发确认线程全部收敛");

        assertEquals(1, confirmed.get(), "并发确认恰好一次生效");
        assertEquals(threads - 1, idempotentHits.get(), "其余均为幂等命中");
        SettlementOrder latest = settlementService.requireLatest(platformScope(), order.getSettlementId());
        assertEquals(SettlementStatus.CONFIRMED, latest.getStatus());
        assertEquals("platform_admin", latest.getConfirmedBy());
        assertEquals(BASE.plusMinutes(11), latest.getConfirmedAt(), "首条留痕为准");
        assertFalse(settlementService.confirm(platformScope(), order.getSettlementId(),
                "platform_admin", BASE.plusMinutes(12)), "事后再确认仍幂等");
        assertEquals(BASE.plusMinutes(11), settlementService.requireLatest(platformScope(),
                order.getSettlementId()).getConfirmedAt(), "留痕不被覆盖");
    }

    @Test
    void 确认越角色与跨租户拒绝() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-confirm-guard", "platform_admin", BASE.plusMinutes(10));
        assertThrows(IllegalStateException.class, () -> settlementService.confirm(
                stationScope(STATION), order.getSettlementId(), "station_admin", BASE.plusMinutes(11)),
                "场站角色禁止确认（DataScope 拦截）");
        assertThrows(IllegalStateException.class, () -> settlementService.confirm(
                otherTenantScope(), order.getSettlementId(), "platform_admin", BASE.plusMinutes(11)),
                "跨租户确认拒绝");
        assertThrows(IllegalStateException.class, () -> settlementService.confirm(
                emptyScope(), order.getSettlementId(), "platform_admin", BASE.plusMinutes(11)),
                "空范围禁止确认");
    }

    // ---------------- 付款登记：仅模拟、重试幂等 ----------------

    @Test
    void 付款登记仅模拟且重试幂等() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-pay", "platform_admin", BASE.plusMinutes(10));

        // 未确认拒绝付款登记
        IllegalStateException notConfirmed = assertThrows(IllegalStateException.class, () ->
                settlementService.registerPayment(platformScope(), order.getSettlementId(),
                        "PAY-early", "platform_admin", BASE.plusMinutes(11)));
        assertTrue(notConfirmed.getMessage().contains("未确认"), "先确认后付款: " + notConfirmed.getMessage());

        settlementService.confirm(platformScope(), order.getSettlementId(), "platform_admin", BASE.plusMinutes(11));
        PaymentRecord record = settlementService.registerPayment(platformScope(), order.getSettlementId(),
                "PAY-1", "platform_admin", BASE.plusMinutes(12));
        assertEquals(PaymentRecord.CHANNEL_SIMULATED, record.getChannel(), "仅登记模拟结果");
        assertEquals(order.getNetYuan(), record.getAmountYuan());
        assertEquals(1, record.getBillVersion(), "版本留痕");
        assertEquals(SettlementStatus.PAID, settlementService.requireLatest(platformScope(),
                order.getSettlementId()).getStatus());

        // 同请求标识重试幂等：返回原记录
        PaymentRecord retry = settlementService.registerPayment(platformScope(), order.getSettlementId(),
                "PAY-1", "platform_admin", BASE.plusMinutes(13));
        assertSame(record, retry, "重试返回原付款记录");
        // 不同请求标识重复登记拒绝
        IllegalStateException duplicate = assertThrows(IllegalStateException.class, () ->
                settlementService.registerPayment(platformScope(), order.getSettlementId(),
                        "PAY-2", "platform_admin", BASE.plusMinutes(13)));
        assertTrue(duplicate.getMessage().contains("重复登记被拒绝"), "防重复付款: " + duplicate.getMessage());
    }

    @Test
    void 已登记付款的单据禁止更正() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-paid-correction", "platform_admin", BASE.plusMinutes(10));
        settlementService.confirm(platformScope(), order.getSettlementId(), "platform_admin", BASE.plusMinutes(11));
        settlementService.registerPayment(platformScope(), order.getSettlementId(),
                "PAY-lock", "platform_admin", BASE.plusMinutes(12));

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                settlementService.correct(platformScope(), order.getSettlementId(), "COR-paid",
                        2.0, null, "迟到样点补计量", "platform_admin", BASE.plusMinutes(13)));
        assertTrue(e.getMessage().contains("禁止更正"), "账实锁定: " + e.getMessage());
    }

    @Test
    void 付款登记越角色拒绝() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder order = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-pay-guard", "platform_admin", BASE.plusMinutes(10));
        settlementService.confirm(platformScope(), order.getSettlementId(), "platform_admin", BASE.plusMinutes(11));
        assertThrows(IllegalStateException.class, () -> settlementService.registerPayment(
                stationScope(STATION), order.getSettlementId(), "PAY-x", "station_admin",
                BASE.plusMinutes(12)), "场站角色禁止付款登记");
    }

    // ---------------- 更正：版本链、差额复算、幂等 ----------------

    @Test
    void 更正版本链留存且差额可复算() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder v1 = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-correction", "platform_admin", BASE.plusMinutes(10));
        settlementService.confirm(platformScope(), v1.getSettlementId(), "platform_admin", BASE.plusMinutes(11));

        // 迟到样点补计量：核定 1.0 → 2.0 kWh，差额 +2.50
        SettlementOrder v2 = settlementService.correct(platformScope(), v1.getSettlementId(),
                "COR-1", 2.0, null, "迟到样点补计量，核定电量上调", "platform_admin", BASE.plusMinutes(12));
        assertEquals(2, v2.getBillVersion(), "更递增版本");
        assertEquals(new BigDecimal("5.00"), v2.getGrossYuan());
        assertEquals(new BigDecimal("5.00"), v2.getNetYuan());
        assertEquals(SettlementStatus.CREATED, v2.getStatus(), "新版本从已生成起步，待重新确认");
        assertEquals(new BigDecimal("3.50"), amountOf(v2, AllocationParty.STATION), "场站分成同步重算");

        // 二次更正：核定 2.0 → 1.5 kWh 且补考核扣款 0.50，净实收 3.25，差额 −1.75
        SettlementOrder v3 = settlementService.correct(platformScope(), v1.getSettlementId(),
                "COR-2", 1.5, new BigDecimal("0.50"), "复核下调核定电量并补记考核扣款",
                "platform_admin", BASE.plusMinutes(13));
        assertEquals(3, v3.getBillVersion());
        assertEquals(new BigDecimal("3.75"), v3.getGrossYuan());
        assertEquals(new BigDecimal("0.50"), v3.getDeductionYuan());
        assertEquals(new BigDecimal("3.25"), v3.getNetYuan());

        // 历史版本留存：v1 原样可查且不被覆盖（确认留痕仍在）
        List<SettlementOrder> history = settlementService.history(platformScope(), v1.getSettlementId());
        assertEquals(3, history.size(), "全版本链留存");
        assertEquals(new BigDecimal("2.50"), history.get(0).getNetYuan(), "v1 净实收原样留存");
        assertEquals(SettlementStatus.CONFIRMED, history.get(0).getStatus(), "v1 确认状态不被新版本覆盖");
        assertSame(v1.getRuleSnapshot(), history.get(1).getRuleSnapshot(), "规则快照随单不变");
        assertSame(v1.getRuleSnapshot(), history.get(2).getRuleSnapshot(), "更正沿用原规则快照");

        // 更正记录齐备且差额可复算
        List<CorrectionRecord> corrections = settlementService.corrections(platformScope());
        assertEquals(2, corrections.size());
        CorrectionRecord first = corrections.get(0);
        assertEquals("COR-1", first.getCorrectionRequestId());
        assertEquals(1, first.getFromVersion());
        assertEquals(2, first.getToVersion());
        assertEquals("platform_admin", first.getOperator());
        assertEquals(BASE.plusMinutes(12), first.getCorrectedAt());
        assertEquals("迟到样点补计量，核定电量上调", first.getReason());
        // 差额复算：由前后版本净实收独立推得
        assertEquals(history.get(1).getNetYuan().subtract(history.get(0).getNetYuan()),
                first.getDiffYuan(), "差额 = 后版本净实收 − 前版本净实收");
        assertEquals(new BigDecimal("2.50"), first.getDiffYuan());
        assertEquals(history.get(2).getNetYuan().subtract(history.get(1).getNetYuan()),
                corrections.get(1).getDiffYuan());
        assertEquals(new BigDecimal("-1.75"), corrections.get(1).getDiffYuan(), "下调差额为负");
    }

    @Test
    void 更正幂等_同请求标识不重复生成版本() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder v1 = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-cor-idem", "platform_admin", BASE.plusMinutes(10));
        SettlementOrder v2 = settlementService.correct(platformScope(), v1.getSettlementId(),
                "COR-idem", 2.0, null, "迟到样点补计量", "platform_admin", BASE.plusMinutes(11));
        SettlementOrder retry = settlementService.correct(platformScope(), v1.getSettlementId(),
                "COR-idem", 2.0, null, "迟到样点补计量", "platform_admin", BASE.plusMinutes(12));
        assertSame(v2, retry, "幂等返回已生成版本");
        assertEquals(2, settlementService.history(platformScope(), v1.getSettlementId()).size(),
                "不重复生成版本");
        assertEquals(1, settlementService.corrections(platformScope()).size(), "不重复留痕");

        // 原生成请求标识在更正后仍返回该单最新版本
        SettlementOrder regenerated = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-cor-idem", "platform_admin", BASE.plusMinutes(13));
        assertEquals(2, regenerated.getBillVersion(), "重复生成返回原单最新版本");
    }

    @Test
    void 无效更正拒绝_无原因_负电量_无变化() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder v1 = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-cor-guard", "platform_admin", BASE.plusMinutes(10));
        assertThrows(IllegalArgumentException.class, () -> settlementService.correct(
                platformScope(), v1.getSettlementId(), "COR-no-reason", 2.0, null, " ",
                "platform_admin", BASE.plusMinutes(11)), "更正原因必填");
        assertThrows(IllegalArgumentException.class, () -> settlementService.correct(
                platformScope(), v1.getSettlementId(), "COR-negative", -1.0, null, "负电量",
                "platform_admin", BASE.plusMinutes(11)), "更正电量不得为负");
        IllegalStateException noChange = assertThrows(IllegalStateException.class, () ->
                settlementService.correct(platformScope(), v1.getSettlementId(), "COR-no-change",
                        1.0, null, "金额不变", "platform_admin", BASE.plusMinutes(11)));
        assertTrue(noChange.getMessage().contains("无需更正"), "无差额拒绝出版本: " + noChange.getMessage());
    }

    @Test
    void 更正跨租户与越角色拒绝() {
        AssessmentRecord assessment = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        SettlementOrder v1 = settlementService.create(platformScope(), assessment.getAssessmentId(),
                PRICE, null, rule(), "REQ-cor-scope", "platform_admin", BASE.plusMinutes(10));
        assertThrows(IllegalStateException.class, () -> settlementService.correct(
                otherTenantScope(), v1.getSettlementId(), "COR-cross", 2.0, null,
                "跨租户更正", "platform_admin", BASE.plusMinutes(11)), "跨租户更正拒绝");
        assertThrows(IllegalStateException.class, () -> settlementService.correct(
                stationScope(STATION), v1.getSettlementId(), "COR-station", 2.0, null,
                "场站角色更正", "station_admin", BASE.plusMinutes(11)), "场站角色禁止更正");
    }

    // ---------------- 数据范围查询 ----------------

    @Test
    void 查询按数据范围收窄且空范围短路() {
        AssessmentRecord own = confirmedAssessment(STATION, RESOURCE, 100.0, 40.0);
        AssessmentRecord other = confirmedAssessment(OTHER_STATION, RESOURCE, 100.0, 40.0);
        settlementService.create(platformScope(), own.getAssessmentId(), PRICE, null,
                rule(), "REQ-q-1", "platform_admin", BASE.plusMinutes(10));
        settlementService.create(platformScope(), other.getAssessmentId(), PRICE, null,
                rule(), "REQ-q-2", "platform_admin", BASE.plusMinutes(10));

        assertEquals(2, settlementService.query(platformScope()).size(), "平台见本租户结算单");
        assertEquals(1, settlementService.query(stationScope(STATION)).size(), "场站仅见所属明细");
        assertEquals(0, settlementService.query(stationScope("cs-station-99")).size(), "范围外不可见");
        assertEquals(0, settlementService.query(emptyScope()).size(), "空范围短路返回空");
        // 越权读单拒绝（未登记与无权限口径一致，由调用方统一映射 404）
        assertThrows(IllegalArgumentException.class, () -> settlementService.requireLatest(
                stationScope(STATION), "STL-not-exists"), "未登记一律拒绝");
    }

    // ---------------- 辅助 ----------------

    /** 三分量分账规则（0.10 / 0.20 / 0.70） */
    private static SharingRuleSnapshot rule() {
        return SharingRuleSnapshot.teaching(new BigDecimal("0.10"), new BigDecimal("0.20"),
                new BigDecimal("0.70"));
    }

    /** 核定评估：基线 100 kW 单点冻结，三个有效样点按 actualKw 全窗执行 */
    private AssessmentRecord confirmedAssessment(String stationId, String resourceId,
                                                 double baselineKw, double actualKw) {
        String eventId = nextEvent();
        freezeRegistry.freeze(freeze(eventId, stationId, resourceId, DrDirection.PEAK_SHAVE, baselineKw));
        BaselineFreeze freeze = freezeRegistry.requireFrozen(eventId, stationId, resourceId);
        long w = freeze.windowStartMs();
        return assessmentService.assess(platformScope(), endedEvent(eventId), stationId, resourceId,
                List.of(MeteredSample.effective(w, actualKw),
                        MeteredSample.effective(w + 30_000, actualKw),
                        MeteredSample.effective(w + 60_000, actualKw)), 0, RULE, BASE.plusMinutes(5));
    }

    private String nextEvent() {
        return "EVT-settle-" + sequence.incrementAndGet();
    }

    private static BaselineFreeze freeze(String eventId, String stationId, String resourceId,
                                         DrDirection direction, double baselineKw) {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        return new BaselineFreeze(eventId, TENANT, stationId, resourceId, direction,
                windowStart.minusMinutes(1), windowStart, windowStart.plusSeconds(120),
                "CONFIRMED_DECLARATION_BASELINE", RULE.getRuleVersion(), "BL-v1",
                List.of(new FrozenBaselinePoint(windowStartMs(windowStart) - 60_000, baselineKw)));
    }

    private static ChargeDrEvent endedEvent(String eventId) {
        ChargeDrEvent event = new ChargeDrEvent(eventId, TENANT, DrDirection.PEAK_SHAVE,
                BASE.plusMinutes(2), BASE.plusMinutes(4), new BigDecimal("100"), BASE.plusMinutes(1));
        event.transitLifecycle(EventLifecycle.PUBLISHED);
        event.transitLifecycle(EventLifecycle.ENDED);
        return event;
    }

    private static long windowStartMs(LocalDateTime windowStart) {
        return windowStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static DataScope platformScope() {
        return DataScopeResolver.resolve(
                IdentityContext.of("platform", TENANT, RoleType.PLATFORM_ADMIN, Set.of()));
    }

    private static DataScope stationScope(String... stations) {
        return DataScopeResolver.resolve(
                IdentityContext.of("station", TENANT, RoleType.STATION_OPERATOR, Set.of(stations)));
    }

    private static DataScope emptyScope() {
        return DataScopeResolver.resolve(
                IdentityContext.of("empty", TENANT, RoleType.STATION_OPERATOR, Set.of()));
    }

    private static DataScope otherTenantScope() {
        return DataScopeResolver.resolve(
                IdentityContext.of("platform", OTHER_TENANT, RoleType.PLATFORM_ADMIN, Set.of()));
    }

    private static BigDecimal amountOf(SettlementOrder order, AllocationParty party) {
        return order.getAllocationLines().stream()
                .filter(line -> line.getParty() == party)
                .map(AllocationLine::getAmountYuan)
                .findFirst()
                .orElseThrow();
    }

    private static String orderPartyId(SettlementOrder order, AllocationParty party) {
        return order.getAllocationLines().stream()
                .filter(line -> line.getParty() == party)
                .map(AllocationLine::getPartyId)
                .findFirst()
                .orElseThrow();
    }
}
