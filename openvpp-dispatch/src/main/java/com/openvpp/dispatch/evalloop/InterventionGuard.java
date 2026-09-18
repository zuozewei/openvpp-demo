package com.openvpp.dispatch.evalloop;

/**
 * 干预闸门 —— 专栏第 46 篇 ⑦ 的滞回防抖。
 *
 * ratio = ePred / eLimit：> interveneInRatio（默认 1.18）进入干预态，
 * &lt; interveneOutRatio（默认 1.15）才退出——进出阈值差 0.03 的滞回带，
 * 防的是临界值附近反复横跳。eLimit ≤ 0 时 ratio 记 0（不会误干预）。
 */
public class InterventionGuard {

    public static final double DEFAULT_IN_RATIO = 1.18;
    public static final double DEFAULT_OUT_RATIO = 1.15;

    private final double inRatio;
    private final double outRatio;
    private boolean intervening = false;

    public InterventionGuard() {
        this(DEFAULT_IN_RATIO, DEFAULT_OUT_RATIO);
    }

    public InterventionGuard(double inRatio, double outRatio) {
        this.inRatio = Math.max(1.0, inRatio);
        // 退出阈值不允许高于进入阈值（否则永远出不去），且不高于 1.0 以上的进入值
        this.outRatio = Math.min(outRatio, this.inRatio);
    }

    /**
     * 每拍喂入预测电量与限额电量，返回本拍是否应处于干预态。
     *
     * @param ePredKwh 预测调节电量（⑦ 的 RegulationEnergyPredictor 产出）
     * @param eLimitKwh 时段限额电量（目标功率 × 0.25h）
     */
    public boolean tick(double ePredKwh, double eLimitKwh) {
        double ratio = eLimitKwh <= 0 ? 0 : ePredKwh / eLimitKwh;
        if (!intervening && ratio > inRatio) {
            intervening = true;
        } else if (intervening && ratio < outRatio) {
            intervening = false;
        }
        return intervening;
    }

    public boolean isIntervening() {
        return intervening;
    }
}
