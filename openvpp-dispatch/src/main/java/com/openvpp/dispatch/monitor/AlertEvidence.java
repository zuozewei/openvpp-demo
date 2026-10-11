package com.openvpp.dispatch.monitor;

/**
 * 告警样点证据 —— 触发告警的最不利样点（第 55 篇"告警记录含样点证据"验收口径）。
 *
 * 只截取判定所需的三个字段（采样时标/接收时标/实际功率），不挂整条样点 ——
 * 告警证据要的是"哪一刻偏差多少"，不是复制遥测流。
 */
public final class AlertEvidence {

    private final long sampledAtMs;
    private final long receivedAtMs;
    private final double powerKw;

    AlertEvidence(long sampledAtMs, long receivedAtMs, double powerKw) {
        this.sampledAtMs = sampledAtMs;
        this.receivedAtMs = receivedAtMs;
        this.powerKw = powerKw;
    }

    public long getSampledAtMs() {
        return sampledAtMs;
    }

    public long getReceivedAtMs() {
        return receivedAtMs;
    }

    public double getPowerKw() {
        return powerKw;
    }

    @Override
    public String toString() {
        return "AlertEvidence{sampledAtMs=" + sampledAtMs
                + ", receivedAtMs=" + receivedAtMs
                + ", powerKw=" + powerKw
                + '}';
    }
}
