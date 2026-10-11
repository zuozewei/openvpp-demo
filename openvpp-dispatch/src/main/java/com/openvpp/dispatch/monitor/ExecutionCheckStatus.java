package com.openvpp.dispatch.monitor;

/**
 * 执行核查状态 —— 单桩指令在监测侧的处置状态（第 55 篇底座）。
 *
 * 与指令状态（{@code CommandState}）分列：指令状态描述"指令走到哪一步"，
 * 本状态描述"监测侧如何处置"。关键口径：
 * <ul>
 *   <li>失联（指令无回执）进入 {@link #OFFLINE_INVESTIGATION} 核查 —— 无回执无遥测
 *       不是"设备未执行"的证据（设备可能已执行但回执丢失），禁止直接断言失败重发；</li>
 *   <li>失联但有遥测回流（回执丢失、设备实际执行了）时，达标判定照常基于遥测证据，
 *       核查状态仍为 OFFLINE_INVESTIGATION，两件事分列。</li>
 * </ul>
 */
public enum ExecutionCheckStatus {

    /** 监测中：指令有回执（受理/拒绝），按遥测证据做达标与偏差判定 */
    MONITORING,

    /** 失联核查：指令无回执（NO_RECEIPT）进入核查 —— 不直接断言设备未执行，
     *  须经超时核查/遥测佐证后由业务层决策（换新编号补发等，见第 54 篇） */
    OFFLINE_INVESTIGATION
}
