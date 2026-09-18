package com.openvpp.dispatch.evalloop;

import java.util.List;

/**
 * 可达率 P30 解析器 —— 专栏第 46 篇「三档信任」。
 *
 * 样本 = 每次完成任务落一条成功率（保留最近 30 条）：
 * <ul>
 *   <li>0 条 → DEFAULT 0.85（初始信任值）；</li>
 *   <li>1 条 → ACTUAL 该样本值——但 &lt;0.60 或 &gt;0.95 视为偏差仍用 0.85，
 *       防单次极端污染承诺（首次 0.98 可能是运气，首次 0.5 可能是事故）；</li>
 *   <li>≥2 条 → P30 样本 30 分位（线性插值）——往小取是保守承诺纪律。</li>
 * </ul>
 */
public class ReachabilityP30 {

    /** 初始信任值 */
    public static final double DEFAULT_REL = 0.85;
    /** 单样本采纳区间 [0.60, 0.95] */
    public static final double FIRST_SAMPLE_MIN = 0.60;
    public static final double FIRST_SAMPLE_MAX = 0.95;

    /**
     * @param samples 成功率样本（旧→新或无序均可，只取数值）
     * @return 解析结果（rel + 模式）
     */
    public Rel resolve(List<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            return new Rel(DEFAULT_REL, Mode.DEFAULT);
        }
        int n = 0;
        for (Double s : samples) {
            if (s != null && !s.isNaN()) {
                n++;
            }
        }
        if (n == 0) {
            return new Rel(DEFAULT_REL, Mode.DEFAULT);
        }
        if (n == 1) {
            double v = clamp(firstValid(samples));
            if (v < FIRST_SAMPLE_MIN || v > FIRST_SAMPLE_MAX) {
                return new Rel(DEFAULT_REL, Mode.DEFAULT);
            }
            return new Rel(v, Mode.ACTUAL);
        }
        double[] clean = samples.stream()
                .filter(s -> s != null && !s.isNaN())
                .mapToDouble(Double::doubleValue)
                .sorted().toArray();
        return new Rel(clamp(percentile(clean, 0.30)), Mode.P30);
    }

    /** 线性插值分位：idx = q×(n−1)，与序统计量取整口径不同（真实工程两套并存，勿混用）。 */
    static double percentile(double[] sorted, double q) {
        double idx = q * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private static double firstValid(List<Double> samples) {
        for (Double s : samples) {
            if (s != null && !s.isNaN()) {
                return s;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static double clamp(double v) {
        return Math.max(0.01, Math.min(1.0, v));
    }

    /** 可达率模式。 */
    public enum Mode {
        DEFAULT, ACTUAL, P30
    }

    /** 解析结果。 */
    public static final class Rel {
        private final double value;
        private final Mode mode;

        Rel(double value, Mode mode) {
            this.value = Math.round(value * 10000.0) / 10000.0;
            this.mode = mode;
        }

        public double value() {
            return value;
        }

        public Mode mode() {
            return mode;
        }
    }
}
