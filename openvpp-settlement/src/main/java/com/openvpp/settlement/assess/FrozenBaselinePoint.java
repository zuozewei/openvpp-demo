package com.openvpp.settlement.assess;

/**
 * 冻结基线样本点 —— 响应开始前采样的基线负荷（第 56 篇"响应前基线冻结"底座）。
 *
 * 冻结时逐点校验采样时刻早于响应窗口开始（响应期数据不入样）；
 * 冻结后样本列表不可变，评估只读本版本。
 */
public final class FrozenBaselinePoint {

    /** 采样时刻（epoch ms，与遥测样点同钟） */
    private final long sampledAtMs;
    /** 基线负荷（kW）；有效零值是真实计量，保留 */
    private final double powerKw;

    public FrozenBaselinePoint(long sampledAtMs, double powerKw) {
        this.sampledAtMs = sampledAtMs;
        this.powerKw = powerKw;
    }

    public long getSampledAtMs() {
        return sampledAtMs;
    }

    public double getPowerKw() {
        return powerKw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FrozenBaselinePoint)) {
            return false;
        }
        FrozenBaselinePoint that = (FrozenBaselinePoint) o;
        return sampledAtMs == that.sampledAtMs
                && Double.compare(powerKw, that.powerKw) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(sampledAtMs, powerKw);
    }

    @Override
    public String toString() {
        return "FrozenBaselinePoint{sampledAtMs=" + sampledAtMs + ", powerKw=" + powerKw + '}';
    }
}
