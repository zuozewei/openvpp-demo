package com.openvpp.dispatch;

import com.openvpp.dispatch.evalloop.ControlFeedbackAnalyzer;
import com.openvpp.dispatch.evalloop.EvalLoopService;
import com.openvpp.dispatch.evalloop.InterventionGuard;
import com.openvpp.dispatch.evalloop.LoopbackPolicy;
import com.openvpp.dispatch.evalloop.ReachabilityP30;
import com.openvpp.dispatch.evalloop.RegulationEnergyPredictor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 46 篇评估闭环单测：反馈判定三分规则 / P30 三档与首样本护档 /
 * 调节能量分段折扣 / 干预滞回 / loopback 夹逼与投诉压顶。
 */
class EvalLoopTest {

    private static ControlFeedbackAnalyzer.ControlLog log(long min, String key, String result,
                                                          String text, String action, String actual) {
        return new ControlFeedbackAnalyzer.ControlLog(min, key, result, text, action, actual);
    }

    @Test
    void 通信异常不进成功率分母() {
        ControlFeedbackAnalyzer analyzer = new ControlFeedbackAnalyzer();
        ControlFeedbackAnalyzer.FeedbackStats stats = analyzer.analyze(Arrays.asList(
                log(0, "d1", "SUCCESS", "ok", "set_temp=26.0", "26.2"),
                log(1, "d2", "FAIL", "通信超时", "set_temp=26.0", "")));
        assertEquals(2, stats.issued());
        assertEquals(1, stats.abnormal(), "通信超时归 abnormal");
        assertEquals(1, stats.valid(), "abnormal 不进分母");
        assertEquals(1.0, stats.successRate(), 0.001, "网络问题不惩罚资源");
    }

    @Test
    void 参数不对齐的成功回执不算成功() {
        ControlFeedbackAnalyzer analyzer = new ControlFeedbackAnalyzer();
        ControlFeedbackAnalyzer.FeedbackStats stats = analyzer.analyze(Collections.singletonList(
                log(0, "d1", "SUCCESS", "ok", "set_temp=20.0", "26.0")));
        assertEquals(0, stats.success(), "回了成功但参数差 6 度，不算成功");
        assertEquals(0.0, stats.successRate(), 0.001);
    }

    @Test
    void 两分钟窗口内延迟回执追认成功() {
        ControlFeedbackAnalyzer analyzer = new ControlFeedbackAnalyzer();
        ControlFeedbackAnalyzer.FeedbackStats stats = analyzer.analyze(Arrays.asList(
                log(0, "d1", "PENDING", "wait", "set_temp=26.0", ""),
                log(2, "d1", "SUCCESS", "ok", "set_temp=26.0", "26.0")));
        assertEquals(2, stats.valid());
        assertEquals(2, stats.success(), "窗口内延迟回执追认前一条 PENDING");
    }

    @Test
    void 可达率三档与首样本护档() {
        ReachabilityP30 p30 = new ReachabilityP30();
        // 0 条 → DEFAULT 0.85
        assertEquals(ReachabilityP30.Mode.DEFAULT, p30.resolve(new ArrayList<>()).mode());
        assertEquals(0.85, p30.resolve(new ArrayList<>()).value(), 0.001);
        // 1 条且在 [0.60,0.95] → ACTUAL
        assertEquals(ReachabilityP30.Mode.ACTUAL,
                p30.resolve(Collections.singletonList(0.90)).mode());
        // 1 条但 0.99（>0.95）→ 仍 DEFAULT
        ReachabilityP30.Rel extreme = p30.resolve(Collections.singletonList(0.99));
        assertEquals(ReachabilityP30.Mode.DEFAULT, extreme.mode(), "首次 0.99 可能是运气，不采纳");
        assertEquals(0.85, extreme.value(), 0.001);
        // ≥2 条 → P30 线性插值：idx=0.3×2=0.6 → 0.6×0.4+0.8×0.6 = 0.72
        //（若按四舍五入取序统计量口径则是 0.8——两套口径并存，勿混用）
        assertEquals(ReachabilityP30.Mode.P30,
                p30.resolve(Arrays.asList(0.6, 0.8, 1.0)).mode());
        assertEquals(0.72, p30.resolve(Arrays.asList(0.6, 0.8, 1.0)).value(), 0.001);
    }

    @Test
    void 调节能量预测分段折扣() {
        RegulationEnergyPredictor predictor = new RegulationEnergyPredictor();
        // <3 分钟：首 3 分钟 0.3kWh 外推 ×5 ×0.7 = 1.05
        assertEquals(1.05, predictor.predict(2, 0.3, 6.0), 0.001, "线性外推 + β1=0.7 折扣");
        // ≥3 分钟未稳定：6kW × 0.25 × 0.8 = 1.2
        assertEquals(1.2, predictor.predict(4, 0.3, 6.0), 0.001, "未稳定 β3=0.8");
        // ≥6 分钟稳定：×0.85
        assertEquals(6.0 * 0.25 * 0.85, predictor.predict(8, 0.3, 6.0), 0.001, "稳定 β2=0.85");
    }

    @Test
    void 干预滞回带防临界抖动() {
        InterventionGuard guard = new InterventionGuard(1.18, 1.15);
        assertFalse(guard.tick(1.10, 1.0), "1.10 未过进入阈值");
        assertTrue(guard.tick(1.19, 1.0), "1.19 进入干预");
        assertTrue(guard.tick(1.16, 1.0), "滞回带内（1.15~1.18）保持干预，不抖动");
        assertFalse(guard.tick(1.10, 1.0), "降到 1.15 以下才退出");
        // eLimit=0 → ratio 记 0，不误干预
        InterventionGuard g2 = new InterventionGuard();
        assertFalse(g2.tick(999, 0), "eLimit 为 0 时不会误干预");
    }

    @Test
    void loopback欠了加码过了收手且夹逼() {
        LoopbackPolicy policy = new LoopbackPolicy();
        // 达成 0.8（欠）→ ×1.03、干预阈值 1.22
        LoopbackPolicy.Advice under = policy.advice(0.8, 0.9, 0.9, 1.0, 0);
        assertEquals(1.03, under.ksScale(), 0.001);
        assertEquals(1.22, under.interveneInRatio(), 0.001);
        // 达成 1.2（超调）→ ×0.97、收紧 1.15
        LoopbackPolicy.Advice over = policy.advice(1.2, 0.9, 0.9, 1.0, 0);
        assertEquals(0.97, over.ksScale(), 0.001);
        assertEquals(1.15, over.interveneInRatio(), 0.001);
        // 多轮累积不得越界 [0.85, 1.15]
        double ks = 1.0;
        for (int i = 0; i < 20; i++) {
            ks = policy.advice(0.5, 0.9, 0.9, ks, 0).ksScale();
        }
        assertEquals(LoopbackPolicy.KS_SCALE_MAX, ks, 0.001, "力度系数夹在 1.15 封顶");
        // 投诉一票压顶：折减封顶 30%，且折减后仍受 0.85 下限夹逼
        LoopbackPolicy.Advice complaint = policy.advice(1.0, 0.9, 0.9, 1.0, 0.5);
        assertEquals(LoopbackPolicy.KS_SCALE_MIN, complaint.ksScale(), 0.001,
                "1.0×(1−0.30)=0.7 被下限夹逼回 0.85");
        // 成功率差 → 敏感档收紧
        assertEquals(+1, policy.advice(1.0, 0.6, 0.9, 1.0, 0).sensitivityDelta());
        assertEquals(-1, policy.advice(1.0, 0.96, 0.96, 1.0, 0).sensitivityDelta());
    }

    @Test
    void 闭环编排达成比与协同度() {
        EvalLoopService service = new EvalLoopService();
        // 基线 100、目标 80（下调 20）、实际 80 → 达成比 1.0、协同度 1.0
        EvalLoopService.EvalResult r = service.evaluate(Arrays.asList(
                        log(0, "d1", "SUCCESS", "ok", "set_temp=26.0", "26.0")),
                100, 80, 80, new ArrayList<>(), 1.0, 0);
        assertEquals(1.0, r.achievementRatio(), 0.001);
        assertEquals(1.0, r.coordinationDegree(), 0.001);
        // 首次样本 1.0（>0.95 不采纳）→ 可达率回落 0.85 DEFAULT
        assertEquals(0.85, r.reachability().value(), 0.001);
    }
}
