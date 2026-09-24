package com.openvpp.aggregator.unit;

/**
 * 准入门槛常量 —— 对应 GB/T 47241-2026 第 4.2 条。
 * 条款为"宜"（推荐性），本身不构成强制验收依据；
 * 本工程将其作为方案初筛默认值，被属地细则、市场规则或合同采用后才构成准入条件。
 */
public final class AdmissionThreshold {

    /** 总聚合容量 10MW */
    public static final long MIN_TOTAL_AGGREGATE_KW = 10_000;

    /** 总调节容量 5MW */
    public static final long MIN_TOTAL_ADJUST_KW = 5_000;

    /** 单元调节容量 1MW：对应 47241 第 4.2 条三层容量体系；44260 第 5.9(c) 的"调节容量宜不低于 1MW"是方案级指标，此处落到单元准入 */
    public static final long MIN_UNIT_ADJUST_KW = 1_000;

    /** 调节速率 %/min：1MW 单元每分钟至少变化 30kW */
    public static final double MIN_RAMP_PERCENT = 3.0;

    /** 持续调节时间 1h */
    public static final long MIN_SUSTAIN_HOURS = 1;

    private AdmissionThreshold() {
    }
}
