package com.openvpp.settlement.assess;

/**
 * 评估结论 —— 效果评估的收口状态（第 56 篇底座）。
 *
 * 证据不足（采样不足/质量门未过/无冻结基线）一律转人工复核，不硬出结论 ——
 * 复核留痕（人/时刻/意见）后由平台人工定性，系统不把缺证据的估算包装成核定结果。
 */
public enum AssessmentConclusion {

    /** 证据齐备，核定响应电量有效，可入结算链路（第 57 篇） */
    CONFIRMED("核定通过"),

    /** 证据不足，转人工复核：核定电量只作参考，待平台复核定性 */
    MANUAL_REVIEW("转人工复核");

    private final String label;

    AssessmentConclusion(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
