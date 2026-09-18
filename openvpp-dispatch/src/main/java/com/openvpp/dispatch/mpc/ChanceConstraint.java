package com.openvpp.dispatch.mpc;

/**
 * 机会约束 —— 「允许以小概率破约束」的参数化表达（专栏第 33 篇）。
 *
 * 教学形态：P(储能 SOC 在响应期间不低于下限) ≥ confidencePct / 100。
 * 置信度从配置中心下发：调频保守（99%）、调峰适中（95%）、套利激进（90%）。
 *
 * 工程铁律：保守度做成可调参数，不做成常量——
 * 求解时按分位数折算为确定性等价约束（本工程不内嵌折算实现，
 * 由调用方按分位数查表折算后注入）。
 */
public final class ChanceConstraint {

    private final String expression;
    private final double confidencePct;

    private ChanceConstraint(String expression, double confidencePct) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("约束表达式不能为空");
        }
        if (confidencePct < 50.0 || confidencePct > 99.9) {
            throw new IllegalArgumentException("置信度应在 50.0~99.9 区间（百分数）: " + confidencePct);
        }
        this.expression = expression;
        this.confidencePct = confidencePct;
    }

    /**
     * 构造机会约束。
     *
     * @param expression    确定性等价约束表达式（如 "soc >= socMin"）
     * @param confidencePct 置信度（百分数，如 95.0 表示允许 5% 概率破约束）
     */
    public static ChanceConstraint of(String expression, double confidencePct) {
        return new ChanceConstraint(expression, confidencePct);
    }

    public String expression() {
        return expression;
    }

    public double confidencePct() {
        return confidencePct;
    }

    /** 对应小概率破约束的允许概率（如 95% → 0.05） */
    public double violationProbability() {
        return (100.0 - confidencePct) / 100.0;
    }

    /**
     * 经验折扣口径的可信容量（第 14 篇起步形态）：可信容量 = 可调容量 × 置信度。
     *
     * 口径警示（第 33 篇）：这是**人工容量折扣**——只反映保守程度的排序，
     * 不构成机会约束意义上的任何概率保证；「95% 可信」的概率承诺必须按
     * 分位数/超越概率从历史分布推导（先定目标超越概率，再取对应低分位），
     * 而不是拿一个乘数去乘。本方法仅供历史分布未积累的起步阶段保守申报。
     *
     * @param adjustableKw 可调容量（kW）
     * @return 经验折扣后的可信容量（kW，保守申报口径）
     */
    public double credibleKw(double adjustableKw) {
        return adjustableKw * confidencePct / 100.0;
    }
}
