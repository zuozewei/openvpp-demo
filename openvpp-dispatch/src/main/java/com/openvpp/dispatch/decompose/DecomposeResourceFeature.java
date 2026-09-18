package com.openvpp.dispatch.decompose;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目标分解的资源特征 —— 专栏第 46 篇四个特征装载器的汇总产物。
 *
 * 教学简化：生产版由预测/可达/配合度/价格四个装载器各自取数落库，
 * 这里收敛为一个可手工构造的纯数据类。两个工程口径保留：
 * <ul>
 *   <li>peReady=false 表示预测缺失，复合得分退化为只用算法分（不硬分任务）；</li>
 *   <li>可调上限缺失时按额定功率×方向比例折算：上调 0.20、下调 0.30。</li>
 * </ul>
 */
public class DecomposeResourceFeature {

    private final String resourceId;
    /** 逐 15 分钟槽：可调上限 kW（Δt=0.25h） */
    private final double[] slotCapKw;
    /** 逐 15 分钟槽：预测负荷 kW（可为空数组表示预测缺失） */
    private final double[] slotForecastKw;
    /** 逐 15 分钟槽：分时电价 元/kWh（仅收益算法需要，可空） */
    private final double[] slotPriceYuan;
    /** 反馈可达率 Rel，无样本回退默认 0.85 */
    private final double reachability;
    /** 配合度 C = 1/(1+偏差均值)，无样本回退默认 0.85 */
    private final double cooperation;
    /** 预测是否就绪（任一槽预测功率 > 0） */
    private final boolean peReady;

    public DecomposeResourceFeature(String resourceId, double[] slotCapKw,
                                    double[] slotForecastKw, double[] slotPriceYuan,
                                    double reachability, double cooperation) {
        this.resourceId = resourceId;
        this.slotCapKw = slotCapKw.clone();
        this.slotForecastKw = slotForecastKw == null ? new double[0] : slotForecastKw.clone();
        this.slotPriceYuan = slotPriceYuan == null ? new double[0] : slotPriceYuan.clone();
        this.reachability = clamp(reachability, 0.01, 1.0);
        this.cooperation = clamp(cooperation, 0.01, 1.0);
        boolean ready = false;
        for (double p : this.slotForecastKw) {
            if (p > 0) {
                ready = true;
                break;
            }
        }
        this.peReady = ready;
    }

    /**
     * 档案兜底：逐时隙可调上限缺失时，额定功率乘方向比例折算。
     *
     * @param ratedKw 额定功率
     * @param up      true=上调（0.20），false=下调削峰（0.30）
     */
    public static DecomposeResourceFeature archiveFallback(String resourceId, double ratedKw,
                                                           boolean up, int slotCount) {
        double[] caps = new double[slotCount];
        double ratio = up ? 0.20 : 0.30;
        for (int i = 0; i < slotCount; i++) {
            caps[i] = round2(ratedKw * ratio);
        }
        return new DecomposeResourceFeature(resourceId, caps, new double[0], new double[0],
                0.85, 0.85);
    }

    /** 档案可调电量 kWh = Σ capKw × 0.25h。 */
    public double capacityKwh() {
        double sum = 0;
        for (double cap : slotCapKw) {
            sum += Math.max(0, cap) * 0.25;
        }
        return round2(sum);
    }

    /** 收益代理 = Σ capKw × 0.25 × 电价（电价缺失按默认 0.83 元/kWh）。 */
    public double benefitYuan() {
        double sum = 0;
        for (int i = 0; i < slotCapKw.length; i++) {
            double price = i < slotPriceYuan.length && slotPriceYuan[i] > 0
                    ? slotPriceYuan[i] : 0.83;
            sum += Math.max(0, slotCapKw[i]) * 0.25 * price;
        }
        return round2(sum);
    }

    public String resourceId() {
        return resourceId;
    }

    public double[] slotCapKw() {
        return slotCapKw.clone();
    }

    public double[] slotForecastKw() {
        return slotForecastKw.clone();
    }

    public double[] slotPriceYuan() {
        return slotPriceYuan.clone();
    }

    public double reachability() {
        return reachability;
    }

    public double cooperation() {
        return cooperation;
    }

    public boolean peReady() {
        return peReady;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 供 SlotDistributor 使用的槽历史稳定分占位（教学版未接历史响应曲线，恒返回缺失）。 */
    public Map<Integer, Double> slotHistoryScore() {
        return new LinkedHashMap<>();
    }
}
