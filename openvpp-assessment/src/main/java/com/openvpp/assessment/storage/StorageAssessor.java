package com.openvpp.assessment.storage;

/**
 * 储能可调容量评估器 —— SOC-功率耦合约束求解。
 *
 * 核心物理（第 04 篇）：功率潜力是 SOC 的函数，
 * 深度放电后后续时段潜力急剧下跌——评估必须沿时间轴滚动求解，不能只看当前值。
 * 另有两条工程硬约束：
 * 1. 站用电率直接扣减可承诺容量；
 * 2. 循环寿命预算：今日已用循环量耗尽则不再申报（哪怕 SOC 允许）。
 *
 * 参照口径：可放电量与净功率均按交流侧可承诺值表述——可用能量已折放电效率，
 * 站用电率仅折减功率口径；电池侧换算只在 SOC 演化（socAfterDischarge）时出现。
 */
public class StorageAssessor {

    private final double pcsRateKw;
    private final double ratedKwh;
    private final double socMinPct;
    private final double socMaxPct;
    private final double efficiencyPct;
    private final double stationUsePct;

    public StorageAssessor(double pcsRateKw, double ratedKwh,
                           double socMinPct, double socMaxPct,
                           double efficiencyPct, double stationUsePct) {
        this.pcsRateKw = pcsRateKw;
        this.ratedKwh = ratedKwh;
        this.socMinPct = socMinPct;
        this.socMaxPct = socMaxPct;
        this.efficiencyPct = efficiencyPct;
        this.stationUsePct = stationUsePct;
    }

    /**
     * 当前时刻可调容量评估。
     *
     * @param socPct 当前 SOC（%）
     * @return 评估结果：放电/充电潜力、可持续时长、是否受寿命预算限制
     */
    public StorageCapability assess(double socPct, double dailyCycleBudgetKwh) {
        double netRateKw = pcsRateKw * (1 - stationUsePct / 100.0);

        // 双向潜力不对称：放电受 (SOC - SOC_min) 约束，充电受 (SOC_max - SOC) 约束
        // 可用能量按效率折算到交流侧（放电乘效率，充电除以效率）
        double dischargeableKwh = Math.max(0, (socPct - socMinPct) / 100.0 * ratedKwh * efficiencyPct / 100.0);
        double chargeableKwh = Math.max(0, (socMaxPct - socPct) / 100.0 * ratedKwh / (efficiencyPct / 100.0));

        double dischargeKw = Math.min(netRateKw, dischargeableKwh > 0 ? netRateKw : 0);
        double chargeKw = Math.min(netRateKw, chargeableKwh > 0 ? netRateKw : 0);

        // 净功率可持续时长（s）：可用能量 ÷ 净功率，教学算例 50% SOC ≈ 2850s（0.79h）
        long sustainSeconds = dischargeKw > 0 ? (long) (dischargeableKwh / dischargeKw * 3600) : 0;

        // 循环寿命预算是置信度开关：预算耗尽则今日不再申报
        boolean cycleBudgetLeft = dailyCycleBudgetKwh > 0;
        if (!cycleBudgetLeft) {
            dischargeKw = 0;
            sustainSeconds = 0;
        }

        return new StorageCapability(dischargeKw, chargeKw, sustainSeconds, cycleBudgetLeft);
    }

    /**
     * 深度放电后 SOC 滚动演化 —— 评估"后续时段潜力衰减"的求解器。
     * 教学算例：50% SOC 以 PCS 额定 2,000kW 放电 2h，SOC 触至 10% 下限，
     * 可用能量清零、放电潜力崩到 0。入参为交流侧放电功率，电池侧消耗按效率折算。
     */
    public double socAfterDischarge(double socPct, double dischargeKw, double hours) {
        double usedKwh = dischargeKw * hours / (efficiencyPct / 100.0);
        double deltaSoc = usedKwh / ratedKwh * 100.0;
        return Math.max(socMinPct, socPct - deltaSoc);
    }

    /** 评估结果 */
    public static class StorageCapability {
        private final double dischargeKw;
        private final double chargeKw;
        private final long sustainSeconds;
        private final boolean cycleBudgetLeft;

        public StorageCapability(double dischargeKw, double chargeKw,
                                 long sustainSeconds, boolean cycleBudgetLeft) {
            this.dischargeKw = dischargeKw;
            this.chargeKw = chargeKw;
            this.sustainSeconds = sustainSeconds;
            this.cycleBudgetLeft = cycleBudgetLeft;
        }

        public double getDischargeKw() { return dischargeKw; }
        public double getChargeKw() { return chargeKw; }
        public long getSustainSeconds() { return sustainSeconds; }
        public boolean isCycleBudgetLeft() { return cycleBudgetLeft; }
    }
}
