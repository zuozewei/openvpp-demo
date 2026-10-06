package com.openvpp.assessment.ac;

/**
 * 空调负荷可调容量评估器 —— ETP（等效热参数）模型。
 *
 * 核心物理（第 04 篇）：建筑热惯性是可调潜力的来源——
 * 关停后温度缓慢漂移的窗口期，就是可承诺的响应时长。
 * 四个工程细节进模型：恢复期、回弹系数、预冷增益、轮停保护。
 */
public class AcLoadAssessor {

    private final double ratedCoolingKw;
    private final double thermalCapKjPerDeg;
    private final double maxTempRiseDeg;

    /** 回弹缓冲系数：教学自选折扣（未实测标定）。为多栋同时恢复留裕量，不构成台变安全的工程保证 */
    private static final double REBOUND_FACTOR = 0.9;
    /** 预冷增益：响应前预冷 2°C，时长近似翻倍 */
    private static final double PRECOOL_GAIN_DEG = 2.0;

    public AcLoadAssessor(double ratedCoolingKw, double thermalCapKjPerDeg, double maxTempRiseDeg) {
        this.ratedCoolingKw = ratedCoolingKw;
        this.thermalCapKjPerDeg = thermalCapKjPerDeg;
        this.maxTempRiseDeg = maxTempRiseDeg;
    }

    /**
     * 完全关停模式评估。
     *
     * @param netHeatGainKw 关停期间净得热（内部热源+太阳辐射-围护散失）
     * @return 可削减功率、可持续时长、恢复期
     */
    public AcCapability assessFullShutdown(double netHeatGainKw, double baseLoadKw) {
        // 恒定净得热热容估算（非动态热模型精确解）：t = C × ΔT / Q_net
        long endureSeconds = (long) (thermalCapKjPerDeg * maxTempRiseDeg / netHeatGainKw);

        // 恢复期：教学简化按 endure 的 40% 估算（比例自选，真实恢复曲线待实测）
        long recoverySeconds = (long) (endureSeconds * 0.4);

        // 可承诺削减 = 基准功率 × 回弹系数
        double committedShedKw = baseLoadKw * REBOUND_FACTOR;

        return new AcCapability(committedShedKw, endureSeconds, recoverySeconds);
    }

    /** 预冷模式：响应前降温 2°C，可容忍温升扩大，时长近似翻倍；预冷下限受舒适温度约束，本工程未建模 */
    public AcCapability assessWithPrecool(double netHeatGainKw, double baseLoadKw) {
        long endureSeconds = (long) (thermalCapKjPerDeg * (maxTempRiseDeg + PRECOOL_GAIN_DEG) / netHeatGainKw);
        long recoverySeconds = (long) (endureSeconds * 0.4);
        return new AcCapability(baseLoadKw * REBOUND_FACTOR, endureSeconds, recoverySeconds);
    }

    /**
     * 压缩机保护校验（教学口径）。
     * 参数契约：cycleSeconds 须传入实际连续停机时长（由停机/重启时间戳计算后传入），
     * 只传"运行+停机"的周期长度挡不住长周期夹短停机的情形。
     * 保护延时按具体设备手册配置，本工程示例 240 秒仅为教学输入、非通用常量；
     * 严格大于判合法属教学裕量策略——等于阈值并不必然物理损伤。
     */
    public boolean rotationCycleValid(int cycleSeconds, int restartGuardSeconds) {
        return cycleSeconds > restartGuardSeconds;
    }

    public static class AcCapability {
        private final double shedKw;
        private final long endureSeconds;
        private final long recoverySeconds;

        public AcCapability(double shedKw, long endureSeconds, long recoverySeconds) {
            this.shedKw = shedKw;
            this.endureSeconds = endureSeconds;
            this.recoverySeconds = recoverySeconds;
        }

        public double getShedKw() { return shedKw; }
        public long getEndureSeconds() { return endureSeconds; }
        public long getRecoverySeconds() { return recoverySeconds; }
        /** 完整周期 = 响应 + 恢复；供后续排程接口使用，当前工程无排程调用 */
        public long fullCycleSeconds() { return endureSeconds + recoverySeconds; }
    }
}
