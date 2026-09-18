package com.openvpp.dispatch.evalloop;

/**
 * 回写策略 —— 专栏第 46 篇 ⑧ loopback 建议：改系数不改页面。
 *
 * 产出的是「资源级叠加层」而非改用户系数表原文：
 * <ul>
 *   <li>达成比 &lt;0.90 → 力度系数 ×1.03、干预阈值放宽 1.22（欠了加码）；</li>
 *   <li>达成比 &gt;1.10 → ×0.97、收紧 1.15（过了收手）；</li>
 *   <li>幅度都只有 3%，ksScale 整体夹在 [0.85, 1.15]；</li>
 *   <li>成功率 &lt;0.70 → 敏感档 +1（更保守）；≥0.95 且协同 ≥0.95 → −1（放权）；</li>
 *   <li>真实投诉一票压顶：系数再乘折减，封顶 30%。</li>
 * </ul>
 */
public class LoopbackPolicy {

    public static final double KS_SCALE_MIN = 0.85;
    public static final double KS_SCALE_MAX = 1.15;
    public static final double COMPLAINT_STEP_CAP = 0.30;

    /**
     * @param achievementRatio 达成比（(基线−实际)/(基线−目标)，可 >1 表超调）
     * @param successRate      成功率
     * @param coordination     协同度
     * @param prevKsScale      上一轮力度系数（首轮传 1.0）
     * @param complaintStep    活跃投诉折减步长（0 = 无投诉；>0 时封顶 0.30）
     * @return 下一轮叠加层建议
     */
    public Advice advice(double achievementRatio, double successRate, double coordination,
                         double prevKsScale, double complaintStep) {
        double ks = prevKsScale;
        double interveneIn = InterventionGuard.DEFAULT_IN_RATIO;
        double interveneDelta = 0.30;
        String complaintLevel = "无";

        if (achievementRatio < 0.90) {
            ks = prevKsScale * 1.03;
            interveneIn = 1.22;
            interveneDelta = 0.25;
            complaintLevel = "轻微";
        } else if (achievementRatio > 1.10) {
            ks = prevKsScale * 0.97;
            interveneIn = 1.15;
            interveneDelta = 0.40;
            complaintLevel = "一般";
        }

        // 真实投诉压顶：宁可少调，不可激怒用户
        if (complaintStep > 0) {
            double step = Math.min(complaintStep, COMPLAINT_STEP_CAP);
            ks = ks * (1 - step);
            if (complaintLevel.equals("无")) {
                complaintLevel = "投诉压顶";
            }
        }
        ks = Math.max(KS_SCALE_MIN, Math.min(KS_SCALE_MAX, ks));

        int sensitivityDelta = 0;
        if (successRate < 0.70 || coordination < 0.70) {
            sensitivityDelta = +1;
        } else if (successRate >= 0.95 && coordination >= 0.95) {
            sensitivityDelta = -1;
        }
        return new Advice(round4(ks), interveneIn, interveneDelta, sensitivityDelta,
                complaintLevel);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 下一轮叠加层建议。 */
    public static final class Advice {
        private final double ksScale;
        private final double interveneInRatio;
        private final double interveneDelta;
        private final int sensitivityDelta;
        private final String complaintLevel;

        Advice(double ksScale, double interveneInRatio, double interveneDelta,
               int sensitivityDelta, String complaintLevel) {
            this.ksScale = ksScale;
            this.interveneInRatio = interveneInRatio;
            this.interveneDelta = interveneDelta;
            this.sensitivityDelta = sensitivityDelta;
            this.complaintLevel = complaintLevel;
        }

        public double ksScale() {
            return ksScale;
        }

        public double interveneInRatio() {
            return interveneInRatio;
        }

        public double interveneDelta() {
            return interveneDelta;
        }

        public int sensitivityDelta() {
            return sensitivityDelta;
        }

        public String complaintLevel() {
            return complaintLevel;
        }
    }
}
