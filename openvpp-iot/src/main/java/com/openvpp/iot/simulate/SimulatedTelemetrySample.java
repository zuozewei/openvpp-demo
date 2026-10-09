package com.openvpp.iot.simulate;

import com.openvpp.iot.timeseries.TelemetryPoint;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 模拟遥测样点 —— 模拟充电桩通道产出的最小遥测单元。
 *
 * 与 {@link TelemetryPoint}（时序写入单元）的关系：样点是"带来源与质量语义的
 * 采集视角"，TelemetryPoint 是"入库视角"；{@link #toTelemetryPoint()} 完成两者
 * 映射——采样时标进 tsMs（时序主键用采样时刻），接收时标与质量标记走监测侧质量
 * 记录，不混进时序主键。
 *
 * 两个时标分列是本类的硬约束（44260 数据校核 + 第 55 篇监测口径）：
 *   sampledAtMs 采样时标 —— 设备侧采样时刻；达标判定、响应积分、曲线横轴以它为准；
 *   receivedAtMs 接收时标 —— 平台收到/投递时刻；迟到判定（接收 − 采样）以它为准。
 * 二者不可互相替代：迟到样点采样时标正常、接收时标晚。缺了任何一个，
 * 监测结论都不可信——所以是两个独立字段，不是一个字段加备注。
 *
 * 来源字段恒为 {@link TelemetryDataSource#SIMULATED}（模拟通道只产模拟值），
 * 字段存在是为与测量值/推算值在数据面上可辨，真实接入实现产出 MEASURED 样点时
 * 复用同一形态。
 */
public class SimulatedTelemetrySample {

    private final String resourceId;
    private final long seq;
    private final long sampledAtMs;
    private final long receivedAtMs;
    private final double powerKw;
    private final TelemetryDataSource source;
    private final Set<TelemetryAnomaly> anomalies;

    public SimulatedTelemetrySample(String resourceId, long seq, long sampledAtMs,
                                    long receivedAtMs, double powerKw,
                                    TelemetryDataSource source,
                                    Set<TelemetryAnomaly> anomalies) {
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.seq = seq;
        this.sampledAtMs = sampledAtMs;
        this.receivedAtMs = receivedAtMs;
        this.powerKw = powerKw;
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(anomalies, "anomalies");
        this.anomalies = anomalies.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(anomalies));
    }

    /** 追加一个异常标记的副本（样点不可变；注入方构造重复/乱序样点时用） */
    SimulatedTelemetrySample withAnomaly(TelemetryAnomaly anomaly) {
        EnumSet<TelemetryAnomaly> extended = anomalies.isEmpty()
                ? EnumSet.noneOf(TelemetryAnomaly.class)
                : EnumSet.copyOf(anomalies);
        extended.add(anomaly);
        return new SimulatedTelemetrySample(resourceId, seq, sampledAtMs,
                receivedAtMs, powerKw, source, extended);
    }

    /** 映射为时序写入单元：采样时标进 tsMs，功率值进 value */
    public TelemetryPoint toTelemetryPoint() {
        return new TelemetryPoint(resourceId, "power", sampledAtMs, powerKw);
    }

    public String getResourceId() {
        return resourceId;
    }

    /** 报文完整性序列号：单调递增，对应 44260 第 5.4 条数据校核的去重基准 */
    public long getSeq() {
        return seq;
    }

    /** 采样时标（设备侧，毫秒） */
    public long getSampledAtMs() {
        return sampledAtMs;
    }

    /** 接收时标（平台侧，毫秒）：迟到判定以它减采样时标 */
    public long getReceivedAtMs() {
        return receivedAtMs;
    }

    /** 实际功率（kW） */
    public double getPowerKw() {
        return powerKw;
    }

    /** 数据来源：模拟通道恒为 SIMULATED */
    public TelemetryDataSource getSource() {
        return source;
    }

    /** 注入异常标记集合：正常样点为空集，不返回 null */
    public Set<TelemetryAnomaly> getAnomalies() {
        return anomalies;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SimulatedTelemetrySample)) {
            return false;
        }
        SimulatedTelemetrySample that = (SimulatedTelemetrySample) o;
        return seq == that.seq
                && sampledAtMs == that.sampledAtMs
                && receivedAtMs == that.receivedAtMs
                && Double.compare(powerKw, that.powerKw) == 0
                && resourceId.equals(that.resourceId)
                && source == that.source
                && anomalies.equals(that.anomalies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceId, seq, sampledAtMs, receivedAtMs, powerKw, source, anomalies);
    }

    @Override
    public String toString() {
        return "SimulatedTelemetrySample{resourceId=" + resourceId
                + ", seq=" + seq
                + ", sampledAtMs=" + sampledAtMs
                + ", receivedAtMs=" + receivedAtMs
                + ", powerKw=" + powerKw
                + ", source=" + source
                + ", anomalies=" + anomalies
                + '}';
    }
}
