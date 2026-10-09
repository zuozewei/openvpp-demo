package com.openvpp.aggregator.plan;

/**
 * 分级计划的分配层级 —— 充电桩需求响应派单的两级结构（第 54 篇底座）。
 *
 * 两级一条链：平台把事件目标切成运营商份额（{@link #PLATFORM_TO_OPERATOR}），
 * 运营商再把份额切到单桩（{@link #OPERATOR_TO_PILE}）。层级决定分配行语义与
 * 证据列形态，不得混用：
 * <ul>
 *   <li>一级行只有调节量与基线（占比分配的结果），不携带绝对目标功率；</li>
 *   <li>二级行必须完成"基线 ± 调节量 → 绝对目标功率"的方向换算，
 *       目标功率与调节量分列留痕，永不混写。</li>
 * </ul>
 */
public enum PlanLevel {

    /** 平台 → 运营商：按已确认申报占比分配，不超申报量，另校验方向可信容量 */
    PLATFORM_TO_OPERATOR,

    /** 运营商 → 桩：按方向可调容量分配，并换算出绝对目标功率 */
    OPERATOR_TO_PILE
}
