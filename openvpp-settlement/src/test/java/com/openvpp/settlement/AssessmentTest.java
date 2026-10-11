package com.openvpp.settlement;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.market.charge.EventLifecycle;
import com.openvpp.settlement.assess.AssessmentConclusion;
import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.AssessmentRule;
import com.openvpp.settlement.assess.AssessmentService;
import com.openvpp.settlement.assess.BaselineFreeze;
import com.openvpp.settlement.assess.BaselineFreezeRegistry;
import com.openvpp.settlement.assess.DirectionalMetering;
import com.openvpp.settlement.assess.FrozenBaselinePoint;
import com.openvpp.settlement.assess.MeteredSample;
import com.openvpp.settlement.assess.MeteringOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 效果评估全场景单测（第 56 篇底座，3.5.5"基线与评估"验收格）：
 * 冻结：响应前冻结五要素齐备 / 锁后拒写（同内容幂等）/ 响应期数据不入样 / 冻结晚于窗口拒绝；
 * 计量：非均匀采样按实际区间积分 / 有效零值保留 / 剔除样点不作证据 / 削峰填谷两方向 /
 *       反向偏差不倒扣 / 看板估算与核定量分列；
 * 评估：版本携带规则与基线版本 / 完整度不足转复核 / 未冻结拒绝评估 / 未结束事件拒绝 /
 *       申请幂等 / 复核留痕幂等 / 数据范围查询与守卫。
 */
class AssessmentTest {

    private static final String TENANT = "tenant-openvpp";
    private static final String OTHER_TENANT = "tenant-other";
    private static final String STATION = "cs-station-01";
    private static final String OTHER_STATION = "cs-station-02";
    private static final String RESOURCE = "pile-cs01-001";
    private static final String EVENT = "EVT-assess-1";
    private static final AssessmentRule RULE = AssessmentRule.teachingDefault();
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 11, 12, 0);

    private final BaselineFreezeRegistry registry = new BaselineFreezeRegistry();
    private final DirectionalMetering metering = new DirectionalMetering();
    private final AssessmentService service = new AssessmentService(registry, metering);

    // ---------------- 冻结：响应前完成 + 锁后拒写 + 质量门 ----------------

    @Test
    void 响应前冻结完成且五要素齐备() {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        BaselineFreeze freeze = freeze(DrDirection.PEAK_SHAVE, windowStart, windowStart.plusSeconds(120), 100.0);

        BaselineFreeze stored = registry.freeze(freeze);
        assertSame(freeze, stored, "登记簿原样返回冻结记录");
        BaselineFreeze required = registry.requireFrozen(EVENT, STATION, RESOURCE);
        assertSame(freeze, required, "requireFrozen 返回同一冻结版本");
        assertEquals(EVENT, required.getEventId());
        assertEquals(STATION, required.getStationId());
        assertEquals(RESOURCE, required.getResourceId());
        assertEquals(DrDirection.PEAK_SHAVE, required.getDirection());
        assertEquals(windowStart.minusMinutes(1), required.getFrozenAt(), "冻结时刻齐备");
        assertEquals("CONFIRMED_DECLARATION_BASELINE", required.getSampleSource(), "样本来源齐备");
        assertEquals(RULE.getRuleVersion(), required.getRuleVersion(), "规则版本齐备");
        assertEquals("BL-v1", required.getBaselineVersion(), "基线版本齐备");
        assertEquals(1, required.getPoints().size());
    }

    @Test
    void 锁后拒写_同键改数一律拒绝() {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, windowStart, windowStart.plusSeconds(120), 100.0));

        // 改数（基线版本变化 = 试图换一套参照系）必须被拒
        BaselineFreeze rewritten = new BaselineFreeze(EVENT, TENANT, STATION, RESOURCE,
                DrDirection.PEAK_SHAVE, windowStart.minusMinutes(1), windowStart, windowStart.plusSeconds(120),
                "CONFIRMED_DECLARATION_BASELINE", RULE.getRuleVersion(), "BL-v2",
                List.of(new FrozenBaselinePoint(windowStartMs(windowStart) - 60_000, 120.0)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.freeze(rewritten));
        assertTrue(e.getMessage().contains("锁后拒写"), "锁后拒写口径: " + e.getMessage());
    }

    @Test
    void 锁后同内容重放幂等返回原冻结() {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        BaselineFreeze freeze = freeze(DrDirection.PEAK_SHAVE, windowStart, windowStart.plusSeconds(120), 100.0);
        registry.freeze(freeze);
        assertSame(freeze, registry.freeze(freeze), "同内容重放幂等返回原冻结版本");
    }

    @Test
    void 响应期数据不入样_采样不早于窗口开始() {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        long windowStartMs = windowStartMs(windowStart);
        // 样本采样时刻恰为窗口开始 —— 属响应期数据，拒绝冻结
        BaselineFreeze polluted = new BaselineFreeze(EVENT, TENANT, STATION, RESOURCE,
                DrDirection.PEAK_SHAVE, windowStart.minusMinutes(1), windowStart, windowStart.plusSeconds(120),
                "CONFIRMED_DECLARATION_BASELINE", RULE.getRuleVersion(), "BL-v1",
                List.of(new FrozenBaselinePoint(windowStartMs, 100.0)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.freeze(polluted));
        assertTrue(e.getMessage().contains("响应期数据不得入样"), "响应期数据入样口径: " + e.getMessage());
    }

    @Test
    void 冻结必须早于响应窗口开始() {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        BaselineFreeze lateFreeze = new BaselineFreeze(EVENT, TENANT, STATION, RESOURCE,
                DrDirection.PEAK_SHAVE, windowStart, windowStart, windowStart.plusSeconds(120),
                "CONFIRMED_DECLARATION_BASELINE", RULE.getRuleVersion(), "BL-v1",
                List.of(new FrozenBaselinePoint(windowStartMs(windowStart) - 60_000, 100.0)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.freeze(lateFreeze));
        assertTrue(e.getMessage().contains("响应前冻结"), "响应前冻结质量门口径: " + e.getMessage());
    }

    @Test
    void 冻结查询按数据范围收窄() {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, windowStart, windowStart.plusSeconds(120), 100.0));

        assertEquals(1, registry.query(platformScope()).size(), "平台见本租户冻结");
        assertEquals(1, registry.query(stationScope(STATION)).size(), "绑定场站见本场站冻结");
        assertEquals(0, registry.query(stationScope(OTHER_STATION)).size(), "他站场站不可见");
        assertEquals(0, registry.query(emptyScope()).size(), "空范围短路返回空");
    }

    // ---------------- 计量：有效区间 + 方向分列 + 两列数分列 ----------------

    @Test
    void 非均匀采样按实际区间积分() {
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        // 非均匀：0s → 30s → 90s，区间 30s + 60s = 90s × 60 kW = 1.5 kWh
        List<MeteredSample> samples = List.of(
                MeteredSample.effective(w, 40),
                MeteredSample.effective(w + 30_000, 40),
                MeteredSample.effective(w + 90_000, 40));
        MeteringOutcome outcome = metering.meter(freeze, samples, 0, RULE);
        assertEquals(1.5, outcome.getConfirmedKwh(), 1e-9, "60 kW × (30+60)s = 1.5 kWh");
        assertEquals(w, outcome.getMeteringStartMs(), "计量区间自首个有效样点");
        assertEquals(w + 90_000, outcome.getMeteringEndMs(), "计量区间至末个有效样点");
    }

    @Test
    void 剔除样点不作证据也不割裂区间() {
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        List<MeteredSample> samples = List.of(
                MeteredSample.effective(w - 10_000, 100),   // 窗外（基线期）样点：不入计量
                MeteredSample.effective(w, 40),
                MeteredSample.excluded(w + 10_000, 999),    // 剔除样点（重复/迟到/指令前）
                MeteredSample.effective(w + 30_000, 40),
                MeteredSample.effective(w + 90_000, 40));
        MeteringOutcome outcome = metering.meter(freeze, samples, 0, RULE);
        assertEquals(1.5, outcome.getConfirmedKwh(), 1e-9, "剔除样点不参与积分，区间不被割裂");
        assertEquals(3, outcome.getEligibleSamples());
        assertEquals(1, outcome.getExcludedSamples(), "仅统计窗内剔除样点");
    }

    @Test
    void 有效零值按全额响应计入() {
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        List<MeteredSample> samples = List.of(
                MeteredSample.effective(w, 0),
                MeteredSample.effective(w + 30_000, 0),
                MeteredSample.effective(w + 60_000, 0));
        MeteringOutcome outcome = metering.meter(freeze, samples, 0, RULE);
        assertEquals(100.0 * 60 / 3600.0, outcome.getConfirmedKwh(), 1e-9,
                "真实零功率（停机执行）按 100 kW 全额响应 × 60 s 计入");
    }

    @Test
    void 削峰填谷两方向分别计量() {
        BaselineFreeze peak = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = peak.windowStartMs();
        MeteringOutcome peakOutcome = metering.meter(peak, List.of(
                MeteredSample.effective(w, 40),
                MeteredSample.effective(w + 30_000, 40),
                MeteredSample.effective(w + 60_000, 40)), 0, RULE);
        assertEquals(60.0 * 60 / 3600.0, peakOutcome.getConfirmedKwh(), 1e-9, "削峰 = Σ(基线−实际)");

        // 填谷冻结另行构造（不经登记簿 —— metering 只读冻结对象本身；同键二次登记会触发锁后拒写）
        LocalDateTime valleyWindowStart = BASE.plusMinutes(2);
        BaselineFreeze valley = freeze(DrDirection.VALLEY_FILL, valleyWindowStart,
                valleyWindowStart.plusSeconds(120), 40.0);
        long wv = valley.windowStartMs();
        MeteringOutcome valleyOutcome = metering.meter(valley, List.of(
                MeteredSample.effective(wv, 100),
                MeteredSample.effective(wv + 30_000, 100),
                MeteredSample.effective(wv + 60_000, 100)), 0, RULE);
        assertEquals(60.0 * 60 / 3600.0, valleyOutcome.getConfirmedKwh(), 1e-9, "填谷 = Σ(实际−基线)");
    }

    @Test
    void 反向偏差不倒扣() {
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        // 实际高于基线（反向偏差）—— 不倒扣，核定 0
        MeteringOutcome outcome = metering.meter(freeze, List.of(
                MeteredSample.effective(w, 140),
                MeteredSample.effective(w + 30_000, 140),
                MeteredSample.effective(w + 60_000, 140)), 0, RULE);
        assertEquals(0.0, outcome.getConfirmedKwh(), 1e-9, "反向偏差不倒扣");
    }

    @Test
    void 看板估算不等于核定量_末点后无证据不外推() {
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        // 有效样点只覆盖前 30 s；末点之后到窗口结束（90 s）无证据 —— 不外推
        List<MeteredSample> samples = List.of(
                MeteredSample.effective(w, 40),
                MeteredSample.effective(w + 30_000, 40));
        MeteringOutcome outcome = metering.meter(freeze, samples, 0, RULE);
        assertEquals(60.0 * 30 / 3600.0, outcome.getConfirmedKwh(), 1e-9, "核定只计有效区间 30 s");
        assertEquals(60.0 * 120 / 3600.0, outcome.getDashboardEstimateKwh(), 1e-9,
                "看板估算 = 均值 × 全窗口 120 s");
        assertNotEquals(outcome.getConfirmedKwh(), outcome.getDashboardEstimateKwh(),
                "看板估算与结算核定量是两列数，不得互相替代");
    }

    @Test
    void 缺失不补零_缺口只入完整度() {
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        // 两个有效样点 + 1 个缺失：缺失时段不产生合成零值区间
        MeteringOutcome outcome = metering.meter(freeze, List.of(
                MeteredSample.effective(w, 40),
                MeteredSample.effective(w + 90_000, 40)), 1, RULE);
        assertEquals(60.0 * 90 / 3600.0, outcome.getConfirmedKwh(), 1e-9,
                "缺失时段不补 0，只按实有有效区间积分");
        assertEquals(2.0 / 3.0, outcome.getCompletenessRatio(), 1e-9, "完整度 = 有效/(有效+缺失) = 2/3");
        assertEquals(1, outcome.getMissingSamples());
    }

    // ---------------- 评估：版本、结论、复核与范围 ----------------

    @Test
    void 评估版本携带规则与基线版本及计量区间() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        BaselineFreeze freeze = registry.requireFrozen(EVENT, STATION, RESOURCE);
        long w = freeze.windowStartMs();
        AssessmentRecord record = service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE),
                STATION, RESOURCE, List.of(
                        MeteredSample.effective(w, 40),
                        MeteredSample.effective(w + 30_000, 40),
                        MeteredSample.effective(w + 60_000, 40)), 0, RULE, BASE.plusMinutes(5));

        assertEquals(AssessmentConclusion.CONFIRMED, record.getConclusion(), "证据齐备出核定结论");
        assertEquals(RULE.getRuleVersion(), record.getRuleVersion(), "评估携带规则版本");
        assertEquals("BL-v1", record.getBaselineVersion(), "评估携带基线版本");
        assertEquals("CONFIRMED_DECLARATION_BASELINE", record.getSampleSource());
        assertEquals(w, record.getMeteringStartMs(), "计量区间随评估留痕");
        assertEquals(w + 60_000, record.getMeteringEndMs());
        assertEquals(60.0 * 60 / 3600.0, record.getConfirmedResponseKwh(), 1e-9);
        assertTrue(record.getReviewReasons().isEmpty(), "核定通过无需复核原因");
    }

    @Test
    void 采样不足转复核不硬出结论() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        BaselineFreeze freeze = registry.requireFrozen(EVENT, STATION, RESOURCE);
        long w = freeze.windowStartMs();
        // 有效样点 2 个（< 3）+ 缺失 8 个：完整度 0.2 —— 证据不足，转人工复核
        AssessmentRecord record = service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE),
                STATION, RESOURCE, List.of(
                        MeteredSample.effective(w, 40),
                        MeteredSample.effective(w + 30_000, 40)), 8, RULE, BASE.plusMinutes(5));

        assertEquals(AssessmentConclusion.MANUAL_REVIEW, record.getConclusion(), "证据不足转人工复核");
        assertEquals(2, record.getReviewReasons().size(), "完整度与有效样点双重不足各登记一条原因");
        assertTrue(record.getReviewReasons().stream().anyMatch(r -> r.contains("完整度不足")));
        assertTrue(record.getReviewReasons().stream().anyMatch(r -> r.contains("有效样点不足")));
    }

    @Test
    void 未冻结基线拒绝评估() {
        ChargeDrEvent event = endedEvent(DrDirection.PEAK_SHAVE);
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.assess(platformScope(), event, STATION, RESOURCE,
                        List.of(MeteredSample.effective(windowStartMs(BASE.plusMinutes(2)), 40)),
                        0, RULE, BASE.plusMinutes(5)));
        assertTrue(e.getMessage().contains("基线未冻结"), "无冻结不评估: " + e.getMessage());
    }

    @Test
    void 未结束事件拒绝评估() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        ChargeDrEvent published = new ChargeDrEvent(EVENT, TENANT, DrDirection.PEAK_SHAVE,
                BASE.plusMinutes(2), BASE.plusMinutes(4), new BigDecimal("100"), BASE.plusMinutes(1));
        published.transitLifecycle(EventLifecycle.PUBLISHED);
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.assess(platformScope(), published, STATION, RESOURCE, List.of(), 0, RULE, BASE));
        assertTrue(e.getMessage().contains("仅已结束事件可申请评估"), "已结束是评估前置: " + e.getMessage());
    }

    @Test
    void 申请评估幂等_同事件场站资源只生成一条() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        BaselineFreeze freeze = registry.requireFrozen(EVENT, STATION, RESOURCE);
        long w = freeze.windowStartMs();
        AssessmentRecord first = service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE),
                STATION, RESOURCE, List.of(
                        MeteredSample.effective(w, 40),
                        MeteredSample.effective(w + 30_000, 40),
                        MeteredSample.effective(w + 60_000, 40)), 0, RULE, BASE.plusMinutes(5));
        AssessmentRecord second = service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE),
                STATION, RESOURCE, List.of(MeteredSample.effective(w, 40)), 9, RULE, BASE.plusMinutes(6));
        assertSame(first, second, "重试不重复生成，返回原评估记录");
    }

    @Test
    void 平台复核留痕且重复复核幂等() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        BaselineFreeze freeze = registry.requireFrozen(EVENT, STATION, RESOURCE);
        long w = freeze.windowStartMs();
        AssessmentRecord record = service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE),
                STATION, RESOURCE, List.of(
                        MeteredSample.effective(w, 40),
                        MeteredSample.effective(w + 30_000, 40)), 8, RULE, BASE.plusMinutes(5));
        assertEquals(AssessmentConclusion.MANUAL_REVIEW, record.getConclusion());

        assertTrue(service.review(platformScope(), record.getAssessmentId(),
                        "platform_admin", "缺测时段重合通信故障，同意按核定值结算", BASE.plusMinutes(6)),
                "首次复核完成留痕");
        AssessmentRecord reviewed = service.require(record.getAssessmentId());
        assertTrue(reviewed.isReviewed());
        assertEquals("platform_admin", reviewed.getReviewedBy());
        assertEquals(BASE.plusMinutes(6), reviewed.getReviewedAt());
        assertEquals("缺测时段重合通信故障，同意按核定值结算", reviewed.getReviewNote());

        assertFalse(service.review(platformScope(), record.getAssessmentId(),
                        "platform_admin", "换个意见", BASE.plusMinutes(7)),
                "重复复核幂等返回，不二次留痕");
        AssessmentRecord again = service.require(record.getAssessmentId());
        assertEquals(BASE.plusMinutes(6), again.getReviewedAt(), "首条留痕为准");
        assertEquals("缺测时段重合通信故障，同意按核定值结算", again.getReviewNote());
    }

    @Test
    void 场站与空范围禁止复核() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        BaselineFreeze freeze = registry.requireFrozen(EVENT, STATION, RESOURCE);
        AssessmentRecord record = service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE),
                STATION, RESOURCE, List.of(MeteredSample.effective(freeze.windowStartMs(), 40)), 8, RULE, BASE.plusMinutes(5));

        assertThrows(IllegalStateException.class, () -> service.review(
                stationScope(STATION), record.getAssessmentId(), "station_admin", "note", BASE.plusMinutes(6)),
                "仅平台可复核");
        assertThrows(IllegalStateException.class, () -> service.review(
                emptyScope(), record.getAssessmentId(), "station_admin", "note", BASE.plusMinutes(6)),
                "空范围禁止操作");
        assertThrows(IllegalStateException.class, () -> service.review(
                otherTenantScope(), record.getAssessmentId(), "platform_admin", "note", BASE.plusMinutes(6)),
                "跨租户禁止复核");
    }

    @Test
    void 评估查询按数据范围收窄且空范围短路() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        BaselineFreeze freeze = registry.requireFrozen(EVENT, STATION, RESOURCE);
        service.assess(platformScope(), endedEvent(DrDirection.PEAK_SHAVE), STATION, RESOURCE,
                List.of(MeteredSample.effective(freeze.windowStartMs(), 40),
                        MeteredSample.effective(freeze.windowStartMs() + 30_000, 40),
                        MeteredSample.effective(freeze.windowStartMs() + 60_000, 40)), 0, RULE, BASE.plusMinutes(5));

        assertEquals(1, service.query(platformScope()).size(), "平台见本租户评估");
        assertEquals(1, service.query(stationScope(STATION)).size(), "绑定场站见本场站评估");
        assertEquals(0, service.query(stationScope(OTHER_STATION)).size(), "他站场站不可见");
        assertEquals(0, service.query(emptyScope()).size(), "空范围短路返回空");
    }

    @Test
    void 申请评估的范围守卫() {
        registry.freeze(freeze(DrDirection.PEAK_SHAVE, BASE.plusMinutes(2), BASE.plusMinutes(4), 100.0));
        ChargeDrEvent event = endedEvent(DrDirection.PEAK_SHAVE);
        List<MeteredSample> samples = List.of(
                MeteredSample.effective(registry.requireFrozen(EVENT, STATION, RESOURCE).windowStartMs(), 40));

        assertThrows(IllegalStateException.class, () -> service.assess(
                stationScope(OTHER_STATION), event, STATION, RESOURCE, samples, 0, RULE, BASE),
                "场站不在范围内拒绝");
        assertThrows(IllegalStateException.class, () -> service.assess(
                emptyScope(), event, STATION, RESOURCE, samples, 0, RULE, BASE),
                "空范围禁止退化");
        assertThrows(IllegalStateException.class, () -> service.assess(
                otherTenantScope(), event, STATION, RESOURCE, samples, 0, RULE, BASE),
                "跨租户拒绝");
    }

    @Test
    void 冻结方向与事件方向不一致拒绝评估() {
        registry.freeze(freeze(DrDirection.VALLEY_FILL, BASE.plusMinutes(2), BASE.plusMinutes(4), 40.0));
        ChargeDrEvent event = endedEvent(DrDirection.PEAK_SHAVE);
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.assess(platformScope(), event, STATION, RESOURCE,
                        List.of(MeteredSample.effective(windowStartMs(BASE.plusMinutes(2)), 40)),
                        0, RULE, BASE.plusMinutes(5)));
        assertTrue(e.getMessage().contains("方向不一致"), "冻结与事件方向须一致: " + e.getMessage());
    }

    // ---------------- 未实现格声明（覆盖矩阵显式列明，不包装成已实现） ----------------

    @Test
    void 反弹与持续性不在本版评估口径内() {
        // 覆盖矩阵显式声明：持续性、反弹未实现（AssessmentRule javadoc 覆盖矩阵）。
        // 行为断言：左阶梯保持语义下，窗口末段反弹样点（实际反超基线）的持有区间
        // 无后继证据，不产生任何方向贡献 —— 这不是"已处理反弹"，反弹识别与扣减
        // 不属于本版规则（不得把该行为包装成反弹处理能力）。
        BaselineFreeze freeze = frozen(DrDirection.PEAK_SHAVE, 100.0);
        long w = freeze.windowStartMs();
        MeteringOutcome outcome = metering.meter(freeze, List.of(
                MeteredSample.effective(w, 40),
                MeteredSample.effective(w + 30_000, 40),
                MeteredSample.effective(w + 60_000, 40),
                MeteredSample.effective(w + 90_000, 160)),  // 窗口末段反弹（实际反超基线）
                0, RULE);
        assertEquals(60.0 * 90 / 3600.0, outcome.getConfirmedKwh(), 1e-9,
                "左阶梯保持：前 90 s 按 60 kW 正向积分，反弹末点无后继持有区间不产生贡献");
        assertEquals(w + 90_000, outcome.getMeteringEndMs(), "反弹样点仍是计量区间末锚点");
    }

    // ---------------- 辅助 ----------------

    /** 构造一条尚未登记的冻结记录（冻结时刻 = 窗口开始前 1 分钟，单点基线） */
    private static BaselineFreeze freeze(DrDirection direction, LocalDateTime windowStart,
                                         LocalDateTime windowEnd, double baselineKw) {
        return new BaselineFreeze(EVENT, TENANT, STATION, RESOURCE, direction,
                windowStart.minusMinutes(1), windowStart, windowEnd,
                "CONFIRMED_DECLARATION_BASELINE", RULE.getRuleVersion(), "BL-v1",
                List.of(new FrozenBaselinePoint(windowStartMs(windowStart) - 60_000, baselineKw)));
    }

    /** 登记一条冻结并取出（metering 测试用） */
    private BaselineFreeze frozen(DrDirection direction, double baselineKw) {
        LocalDateTime windowStart = BASE.plusMinutes(2);
        return registry.freeze(freeze(direction, windowStart, windowStart.plusSeconds(120), baselineKw));
    }

    /** 已结束事件（评估前置：生命周期 ENDED） */
    private static ChargeDrEvent endedEvent(DrDirection direction) {
        ChargeDrEvent event = new ChargeDrEvent(EVENT, TENANT, direction,
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
}
