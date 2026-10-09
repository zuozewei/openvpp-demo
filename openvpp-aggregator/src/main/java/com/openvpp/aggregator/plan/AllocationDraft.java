package com.openvpp.aggregator.plan;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分配草案 —— 约束分配器的一级/二级分配输出（第 54 篇底座）。
 *
 * 守恒口径：{@link #allocatedKw()} + {@link #getGapKw()} = 目标调节量，恒等式在
 * 构造时校验，不满足即拒绝出生 —— "部分分配成功"绝不写成"总目标可兑现"。
 *
 * 缺口语义：gapKw &gt; 0 表示申报/可用容量不足，草案仍交付（供运营决策），
 * 但由计划确认阶段阻止下发；运营路径三选一（见 {@link AggregatePlan#confirm}）。
 */
public final class AllocationDraft {

    private final BigDecimal targetAdjustKw;
    private final BigDecimal gapKw;
    private final List<AllocationLine> lines;

    AllocationDraft(BigDecimal targetAdjustKw, BigDecimal gapKw, List<AllocationLine> lines) {
        if (targetAdjustKw == null || targetAdjustKw.signum() < 0) {
            throw new IllegalArgumentException("目标调节量不得为负: " + targetAdjustKw);
        }
        if (gapKw == null || gapKw.signum() < 0) {
            throw new IllegalArgumentException("缺口不得为负: " + gapKw);
        }
        if (lines == null) {
            throw new IllegalArgumentException("分配行清单不能为空引用");
        }
        BigDecimal allocated = lines.stream()
                .map(AllocationLine::getAdjustKw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.add(gapKw).compareTo(targetAdjustKw) != 0) {
            throw new IllegalStateException("分配不守恒: 各行合计 " + allocated + " kW + 缺口 " + gapKw
                    + " kW ≠ 目标 " + targetAdjustKw + " kW");
        }
        this.targetAdjustKw = targetAdjustKw;
        this.gapKw = gapKw;
        this.lines = List.copyOf(lines);
    }

    /** 目标调节量（kW） */
    public BigDecimal getTargetAdjustKw() {
        return targetAdjustKw;
    }

    /** 已分配量合计（kW） = 目标 − 缺口 */
    public BigDecimal allocatedKw() {
        return targetAdjustKw.subtract(gapKw);
    }

    /**
     * 分配缺口（kW）：0 表示可确认可下发；&gt; 0 表示申报不足，
     * 确认与下发被阻止，须运营三选一处置（拒绝 / 补申报 / 降目标）。
     */
    public BigDecimal getGapKw() {
        return gapKw;
    }

    /** 缺口为零、可进入确认与下发 */
    public boolean isFeasible() {
        return gapKw.signum() == 0;
    }

    /** 分配行（按分配对象标识升序，不可变） */
    public List<AllocationLine> getLines() {
        return lines;
    }
}
