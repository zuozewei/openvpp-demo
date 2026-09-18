package com.openvpp.dispatch.evalloop;

import java.util.List;

/**
 * 效果评估闭环服务 —— 专栏第 46 篇 ⑧ 的编排（十步生产版的教学浓缩）。
 *
 * 反馈判定 → 达成比/协同度 → 成功率样本 → P30 可达率 → loopback 建议。
 * 失败只打日志不抛异常（生产口径）：评估是任务后的收尾环节，
 * 不能反过来打断调度主链路。
 */
public class EvalLoopService {

    private final ControlFeedbackAnalyzer analyzer;
    private final ReachabilityP30 reachabilityP30;
    private final LoopbackPolicy loopbackPolicy;

    public EvalLoopService() {
        this(new ControlFeedbackAnalyzer(), new ReachabilityP30(), new LoopbackPolicy());
    }

    public EvalLoopService(ControlFeedbackAnalyzer analyzer, ReachabilityP30 reachabilityP30,
                           LoopbackPolicy loopbackPolicy) {
        this.analyzer = analyzer;
        this.reachabilityP30 = reachabilityP30;
        this.loopbackPolicy = loopbackPolicy;
    }

    /**
     * 评估一次已完成的调度任务。
     *
     * @param logs            任务控制日志
     * @param baselineKwh     基线电量
     * @param targetKwh       目标电量（带符号，下调为负）
     * @param actualKwh       实际电量
     * @param historySamples  该资源历史成功率样本（不含本次）
     * @param prevKsScale     上一轮力度系数
     * @param complaintStep   活跃投诉折减步长（0 = 无）
     * @return 评估结果
     */
    public EvalResult evaluate(List<ControlFeedbackAnalyzer.ControlLog> logs,
                               double baselineKwh, double targetKwh, double actualKwh,
                               List<Double> historySamples, double prevKsScale,
                               double complaintStep) {
        ControlFeedbackAnalyzer.FeedbackStats stats = analyzer.analyze(logs);

        // 达成比 = (基线−实际)/(基线−目标)，可 >1 表超调；协同度 = 1−|达成比−1|
        double achievement = achievementRatio(baselineKwh, targetKwh, actualKwh,
                stats.successRate());
        double coordination = Math.max(0, Math.min(1, 1 - Math.abs(achievement - 1)));

        // 本次成功率落一条样本 → 与历史合并取 P30
        List<Double> merged = new java.util.ArrayList<>(historySamples);
        merged.add(stats.successRate());
        ReachabilityP30.Rel rel = reachabilityP30.resolve(merged);

        LoopbackPolicy.Advice advice = loopbackPolicy.advice(achievement,
                stats.successRate(), coordination, prevKsScale, complaintStep);

        return new EvalResult(stats, achievement, coordination, rel, advice);
    }

    /** 达成比；无曲线（基线≈目标）时退贴进度，再无成功率兜底 0。 */
    static double achievementRatio(double baseline, double target, double actual,
                                   double successRateFallback) {
        double want = baseline - target;
        if (Math.abs(want) < 1e-6) {
            // 目标≈基线：用贴进度（越接近目标越大）
            double gap = Math.abs(baseline - actual);
            return Math.abs(baseline) < 1e-6 ? successRateFallback
                    : Math.max(0, 1 - gap / Math.abs(baseline));
        }
        return (baseline - actual) / want;
    }

    /** 一次任务的评估结果。 */
    public static final class EvalResult {
        private final ControlFeedbackAnalyzer.FeedbackStats stats;
        private final double achievementRatio;
        private final double coordinationDegree;
        private final ReachabilityP30.Rel reachability;
        private final LoopbackPolicy.Advice advice;

        EvalResult(ControlFeedbackAnalyzer.FeedbackStats stats, double achievementRatio,
                   double coordinationDegree, ReachabilityP30.Rel reachability,
                   LoopbackPolicy.Advice advice) {
            this.stats = stats;
            this.achievementRatio = achievementRatio;
            this.coordinationDegree = coordinationDegree;
            this.reachability = reachability;
            this.advice = advice;
        }

        public ControlFeedbackAnalyzer.FeedbackStats stats() {
            return stats;
        }

        public double achievementRatio() {
            return achievementRatio;
        }

        public double coordinationDegree() {
            return coordinationDegree;
        }

        public ReachabilityP30.Rel reachability() {
            return reachability;
        }

        public LoopbackPolicy.Advice advice() {
            return advice;
        }
    }
}
