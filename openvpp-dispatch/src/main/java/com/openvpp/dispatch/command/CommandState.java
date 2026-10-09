package com.openvpp.dispatch.command;

/**
 * 单桩指令状态 —— 充电桩下行指令的生命周期（第 54 篇底座）。
 *
 * 状态链：CREATED（已登记未发送）→ SENT（已发送待回执）→ 三分支：
 * <ul>
 *   <li>ACKED：设备受理回执（只证明受理，不证明功率到位 —— 达标留给后续监测工序）；</li>
 *   <li>REJECTED：设备拒绝（原因码留痕，可重决策后换新编号补发）；</li>
 *   <li>NO_RECEIPT：失联无回执，进入核查 —— 无回执 ≠ 设备未执行，
 *       禁止直接重发（设备可能已执行，重复下发放大为重复动作），
 *       须经超时核查/遥测佐证后由业务层换新编号补发。</li>
 * </ul>
 */
public enum CommandState {

    /** 已创建未发送 */
    CREATED,

    /** 已发送，等待设备回执 */
    SENT,

    /** 设备已受理（回执 ACCEPTED；受理 ≠ 到位） */
    ACKED,

    /** 设备已拒绝（回执 REJECTED，原因码留痕） */
    REJECTED,

    /** 失联无回执，进入核查（不是失败断言） */
    NO_RECEIPT;

    /**
     * 是否允许换新编号补发：仅无回执与已拒绝可补发。
     * 已受理指令在途，补发会放大为设备重复动作 —— 已受理指令的变更走取消/修订，不走补发。
     */
    public boolean isReissuable() {
        return this == NO_RECEIPT || this == REJECTED;
    }
}
