package com.openvpp.settlement.assess;

/**
 * 方向性计量结果 —— 评估记录的质量与电量载体（第 56 篇底座）。
 *
 * 两列数严格分列（3.5.5"看板估算与核定量分列"）：
 * <ul>
 *   <li>confirmedKwh：核定响应电量 —— 只按有效区间的方向性积分，是结算的唯一依据；</li>
 *   <li>dashboardEstimateKwh：实时看板进度估算（有效样点方向偏差均值 × 全事件时长），
 *       仅供过程观察，绝不作核定依据 —— 非均匀采样或缺测时两列数必然背离。</li>
 * </ul>
 *
 * 完整度 = 有效样点数 /（有效样点数 + 缺失样点数），入评估结论；
 * 剔除样点（重复/迟到/指令前）是传输与关联 artifact，不计入完整度分母。
 */
public final class MeteringOutcome {

    /** 核定响应电量（kWh，有效区间方向性积分） */
    private final double confirmedKwh;
    /** 看板进度估算（kWh，均值 × 全窗口时长；不作核定依据） */
    private final double dashboardEstimateKwh;
    /** 计量区间：首/末有效样点采样时刻（epoch ms） */
    private final long meteringStartMs;
    private final long meteringEndMs;
    /** 有效样点数（质量门通过） */
    private final int eligibleSamples;
    /** 剔除样点数（窗内重复/迟到/指令前） */
    private final int excludedSamples;
    /** 缺失样点数（序号缺口，不补 0） */
    private final int missingSamples;
    /** 完整度 = 有效 /（有效 + 缺失） */
    private final double completenessRatio;

    public MeteringOutcome(double confirmedKwh, double dashboardEstimateKwh,
                           long meteringStartMs, long meteringEndMs,
                           int eligibleSamples, int excludedSamples,
                           int missingSamples, double completenessRatio) {
        this.confirmedKwh = confirmedKwh;
        this.dashboardEstimateKwh = dashboardEstimateKwh;
        this.meteringStartMs = meteringStartMs;
        this.meteringEndMs = meteringEndMs;
        this.eligibleSamples = eligibleSamples;
        this.excludedSamples = excludedSamples;
        this.missingSamples = missingSamples;
        this.completenessRatio = completenessRatio;
    }

    public double getConfirmedKwh() {
        return confirmedKwh;
    }

    public double getDashboardEstimateKwh() {
        return dashboardEstimateKwh;
    }

    public long getMeteringStartMs() {
        return meteringStartMs;
    }

    public long getMeteringEndMs() {
        return meteringEndMs;
    }

    public int getEligibleSamples() {
        return eligibleSamples;
    }

    public int getExcludedSamples() {
        return excludedSamples;
    }

    public int getMissingSamples() {
        return missingSamples;
    }

    public double getCompletenessRatio() {
        return completenessRatio;
    }

    @Override
    public String toString() {
        return "MeteringOutcome{confirmedKwh=" + confirmedKwh
                + ", dashboardEstimateKwh=" + dashboardEstimateKwh
                + ", metering=[" + meteringStartMs + "," + meteringEndMs + "]"
                + ", eligible=" + eligibleSamples + ", excluded=" + excludedSamples
                + ", missing=" + missingSamples + ", completeness=" + completenessRatio + '}';
    }
}
