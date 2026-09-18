package com.openvpp.dispatch.evalloop;

/**
 * 调节电量预测器 —— 专栏第 46 篇 ⑦ 达成监管的 ePred。
 *
 * 响应初期数据稀疏，分段处理：
 * <ul>
 *   <li>开始后 &lt;3 分钟：首 3 分钟电量线性外推到 15 分钟，再打 β1=0.7 折扣——
 *       外推本身高估，折扣纠偏；</li>
 *   <li>开始后 ≥3 分钟：平均功率 × 0.25h × β，运行稳定（≥6 分钟）β2=0.85、
 *       未稳定 β3=0.8——波动期再让一点。</li>
 * </ul>
 */
public class RegulationEnergyPredictor {

    public static final double BETA_EXTRAPOLATE = 0.7;
    public static final double BETA_STABLE = 0.85;
    public static final double BETA_UNSTABLE = 0.8;
    /** 判定「稳定」的运行时长（分钟） */
    public static final int STABLE_AFTER_MIN = 6;

    /**
     * @param elapsedMin  已运行分钟数
     * @param first3MinKwh 首 3 分钟实际调节电量（kWh）
     * @param avgPowerKw  平均调节功率（kW，≈ 前 3 分钟平均）
     * @return 本时段（15 分钟）调节电量预测（kWh）
     */
    public double predict(double elapsedMin, double first3MinKwh, double avgPowerKw) {
        if (elapsedMin < 3) {
            return first3MinKwh * (15.0 / 3.0) * BETA_EXTRAPOLATE;
        }
        double beta = elapsedMin >= STABLE_AFTER_MIN ? BETA_STABLE : BETA_UNSTABLE;
        return avgPowerKw * 0.25 * beta;
    }
}
