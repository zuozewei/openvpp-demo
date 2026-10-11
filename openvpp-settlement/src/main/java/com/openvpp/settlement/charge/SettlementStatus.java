package com.openvpp.settlement.charge;

/**
 * 结算单状态 —— 单据级生命周期（第 57 篇底座，3.5.3 接口契约第 8 行
 * "结算办理：平台生成、确认、付款登记"）。
 *
 * 状态机：
 * CREATED（已生成）→ CONFIRMED（已确认）→ PAID（付款已登记·模拟）。
 *
 * 口径：
 * 1. 付款登记只代表"模拟付款结果已登记"，不接真实资金通道 —— 状态名与
 *    留痕均带"模拟"语义，不得对外表述为真实到账；
 * 2. PAID 为终态：已登记模拟付款的单据禁止更正（防账实不符），
 *    金额调整须走人工流程，教学工程不展开；
 * 3. 更正（correction）不改写任何既有版本：新版本单据以自己的状态机
 *    从 CREATED 起步，历史版本状态原样留存。
 */
public enum SettlementStatus {

    /** 已生成：四量与分账明细已落定，待平台确认 */
    CREATED("已生成"),

    /** 已确认：平台确认单据金额，进入可付款登记状态 */
    CONFIRMED("已确认"),

    /** 付款已登记（模拟）：仅登记模拟付款结果，非真实资金通道 */
    PAID("付款已登记（模拟）");

    private final String label;

    SettlementStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 状态迁移断言：只允许 CREATED→CONFIRMED→PAID 单向前进 */
    public void assertTransitTo(SettlementStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("目标状态不能为空");
        }
        boolean allowed = (this == CREATED && target == CONFIRMED)
                || (this == CONFIRMED && target == PAID);
        if (!allowed) {
            throw new IllegalStateException("结算单状态不允许从 " + this + " 迁移到 " + target);
        }
    }
}
