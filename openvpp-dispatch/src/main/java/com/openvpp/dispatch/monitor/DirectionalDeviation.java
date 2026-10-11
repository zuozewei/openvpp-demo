package com.openvpp.dispatch.monitor;

import com.openvpp.market.charge.DrDirection;

/**
 * 方向性偏差 —— 单桩实测相对目标的偏差结论（第 55 篇底座）。
 *
 * 一次判定只回答一个问题：按事件方向（或零目标的绝对口径），
 * 当前有效证据里偏差最大的样点有没有超差。三列分明：
 * <ul>
 *   <li>deviationKw：有符号偏差 = 实测 − 目标（削峰为正 = 压不下来，
 *       填谷为负 = 抬不上去），留痕不加工；</li>
 *   <li>exceeded：是否超容差（判定列）；</li>
 *   <li>basis：判定基准 —— 零目标为 {@link DeviationBasis#ABSOLUTE_ZERO_TARGET}，
 *       其余为 {@link DeviationBasis#DIRECTIONAL}（比例偏差在零目标时无定义，
 *       不得使用）。</li>
 * </ul>
 *
 * 无有效证据（样点全缺/全迟到/指令前）时不下偏差结论，
 * 由 {@link ChargeExecutionMonitor} 以 null 表示。
 */
public final class DirectionalDeviation {

    private final DrDirection direction;
    private final double deviationKw;
    private final double toleranceKw;
    private final DeviationBasis basis;
    private final boolean exceeded;

    DirectionalDeviation(DrDirection direction, double deviationKw, double toleranceKw,
                         DeviationBasis basis, boolean exceeded) {
        this.direction = java.util.Objects.requireNonNull(direction, "调节方向不能为空");
        this.deviationKw = deviationKw;
        this.toleranceKw = toleranceKw;
        this.basis = java.util.Objects.requireNonNull(basis, "判定基准不能为空");
        this.exceeded = exceeded;
    }

    public DrDirection getDirection() {
        return direction;
    }

    /** 有符号偏差（kW）= 实测 − 目标：削峰为正 = 压不下来，填谷为负 = 抬不上去 */
    public double getDeviationKw() {
        return deviationKw;
    }

    /** 达标/超差容差（kW，教学默认 5 kW，装配可配） */
    public double getToleranceKw() {
        return toleranceKw;
    }

    public DeviationBasis getBasis() {
        return basis;
    }

    /** 是否超容差（判定列；与偏差数值分列） */
    public boolean isExceeded() {
        return exceeded;
    }

    @Override
    public String toString() {
        return "DirectionalDeviation{direction=" + direction
                + ", deviationKw=" + deviationKw
                + ", toleranceKw=" + toleranceKw
                + ", basis=" + basis
                + ", exceeded=" + exceeded
                + '}';
    }
}
