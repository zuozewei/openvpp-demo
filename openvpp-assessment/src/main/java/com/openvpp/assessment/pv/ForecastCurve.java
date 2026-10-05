package com.openvpp.assessment.pv;

import java.time.LocalDateTime;

/**
 * 光伏出力预测曲线 —— 单时刻中心预测 + 未校准的悲观/乐观参考上下界。
 * 44260 评估的产出不是"一个数"，而是"一个数加风险量化"：
 * P_50 为字段占位名（不保证条件中位数），P_90（悲观）用于保守申报，区间未校准、不承诺概率覆盖。
 */
public class ForecastCurve {

    private final LocalDateTime time;

    /** 中心预测（字段占位名 P_50，不保证条件中位数） */
    private final double p50Kw;

    /** 悲观参考下界（字段占位名 P_90，未校准，不承诺概率覆盖） */
    private final double p90Kw;

    /** 乐观参考上界（字段占位名 P_10，未校准，不承诺概率覆盖） */
    private final double p10Kw;

    public ForecastCurve(LocalDateTime time, double p50Kw, double p90Kw, double p10Kw) {
        this.time = time;
        this.p50Kw = p50Kw;
        this.p90Kw = p90Kw;
        this.p10Kw = p10Kw;
    }

    /** 区间绝对宽度（kW）；相对裕量为本值除以中心预测 */
    public double uncertaintyWidth() {
        return p10Kw - p90Kw;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public double getP50Kw() {
        return p50Kw;
    }

    public double getP90Kw() {
        return p90Kw;
    }

    public double getP10Kw() {
        return p10Kw;
    }
}
