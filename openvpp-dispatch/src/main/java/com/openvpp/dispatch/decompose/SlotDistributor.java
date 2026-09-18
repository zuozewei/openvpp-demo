package com.openvpp.dispatch.decompose;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时隙分发器 —— 专栏第 46 篇「资源电量 → 15 分钟功率列」。
 *
 * 每槽权重 w_t = capKw_t × max(φ_t, 0)（φ 因算法而异：比例均衡恒 1；
 * 收益算法取 max(预测负荷×电价, 0)——贵且负荷高的槽多担；协同/可控优先
 * 用该时钟点历史稳定分，缺失退资源级均值，再缺失退 1）。
 *
 * 两条硬纪律：
 * <ol>
 *   <li>末槽补漂移 + 槽上限裁剪回流，保证 ΣkW×Δt 严格等于分配电量——
 *       分解结果直接进策略落库与监管基准，上游差 1% 下游全是噪声；</li>
 *   <li>Σw ≤ 0 时按槽数均分，宁可平均也不抛异常。</li>
 * </ol>
 */
public class SlotDistributor {

    /** 15 分钟时隙（小时） */
    public static final double DELTA_HOURS = 0.25;

    /**
     * 单资源的电量 → 逐槽功率。
     *
     * @param feature 资源特征
     * @param algo    算法类型
     * @param energyKwh 已分配电量（kWh）
     * @return 逐槽功率 kW（长度 = 槽数）
     */
    public double[] distribute(DecomposeResourceFeature feature, DecomposeAlgoType algo,
                               double energyKwh) {
        double[] caps = feature.slotCapKw();
        double[] forecast = feature.slotForecastKw();
        double[] prices = feature.slotPriceYuan();
        int n = caps.length;
        double[] kw = new double[n];
        if (n == 0) {
            return kw;
        }
        double[] phi = new double[n];
        double sumW = 0;
        for (int t = 0; t < n; t++) {
            phi[t] = phi(algo, feature, t,
                    t < forecast.length ? forecast[t] : 0,
                    t < prices.length ? prices[t] : 0);
            sumW += Math.max(0, caps[t]) * Math.max(0, phi[t]);
        }
        if (sumW <= 0) {
            for (int t = 0; t < n; t++) {
                kw[t] = (energyKwh / n) / DELTA_HOURS;
            }
        } else {
            for (int t = 0; t < n; t++) {
                kw[t] = energyKwh * Math.max(0, caps[t]) * Math.max(0, phi[t]) / sumW
                        / DELTA_HOURS;
            }
        }
        // 末槽补漂移：浮点凑整兜底
        double driftKw = (energyKwh - sumKwh(kw)) / DELTA_HOURS;
        kw[n - 1] += driftKw;
        // 槽上限裁剪：被裁电量按槽序回流到仍有余量的槽
        clipToSlotCap(kw, caps);
        return kw;
    }

    /** 分时段权重 φ（教学版未接历史响应曲线，协同/可控退资源级均值）。 */
    static double phi(DecomposeAlgoType algo, DecomposeResourceFeature f, int slot,
                      double forecastKw, double priceYuan) {
        switch (algo) {
            case PROPORTIONAL:
                return 1.0;
            case BENEFIT:
                return Math.max(forecastKw * priceYuan, 0);
            case COOPERATION:
                return f.slotHistoryScore().getOrDefault(slot, f.cooperation());
            case CONTROLLABILITY:
                return f.slotHistoryScore().getOrDefault(slot, f.reachability());
            default:
                return 1.0;
        }
    }

    private void clipToSlotCap(double[] kw, double[] caps) {
        double need = 0;
        for (int t = 0; t < kw.length; t++) {
            if (kw[t] < 0) {
                need += -kw[t];
                kw[t] = 0;
            } else if (kw[t] > caps[t]) {
                need += kw[t] - caps[t];
                kw[t] = caps[t];
            }
        }
        // 被裁电量按槽序回流到仍有余量的槽
        for (int t = 0; t < kw.length && need > 1e-9; t++) {
            double room = caps[t] - kw[t];
            if (room > 1e-9) {
                double add = Math.min(room, need);
                kw[t] += add;
                need -= add;
            }
        }
    }

    private static double sumKwh(double[] kw) {
        double s = 0;
        for (double v : kw) {
            s += v * DELTA_HOURS;
        }
        return s;
    }

    /** 批量分发：分配结果 → 每资源功率列（键序与 allocations 一致）。 */
    public Map<String, double[]> distributeAll(DecomposeAlgoType algo,
                                               List<CompositeAllocator.Allocation> allocations,
                                               Map<String, DecomposeResourceFeature> features) {
        Map<String, double[]> result = new LinkedHashMap<>();
        for (CompositeAllocator.Allocation a : allocations) {
            result.put(a.resourceId(),
                    distribute(features.get(a.resourceId()), algo, a.targetAdjustKwh()));
        }
        return result;
    }
}
