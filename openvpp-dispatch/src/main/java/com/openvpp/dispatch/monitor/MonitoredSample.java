package com.openvpp.dispatch.monitor;

import com.openvpp.iot.simulate.TelemetryDataSource;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 监测样点 —— 遥测入库后的监测视角记录（第 55 篇底座）。
 *
 * 与 iot 侧 {@code SimulatedTelemetrySample}（采集视角）的分工：采集样点描述
 * "设备发了什么"，本类描述"平台判它是什么"——在采集字段之上叠加监测方的
 * 质量判定（{@link SampleQualityMark}）与指令关联（commandNo）。
 *
 * 字段口径（3.5.2"遥测与告警"对象）：资源/指令关联、采样时间、接收时间、
 * 实际功率、数据来源、质量标记，六列齐备，缺一不可：
 * <ul>
 *   <li>采样时标 sampledAtMs：设备侧采样时刻，曲线横轴、达标判定、偏差计算一律以它为准；</li>
 *   <li>接收时标 receivedAtMs：平台收到时刻，迟到判定（接收 − 采样）以它为准；</li>
 *   <li>来源 source：模拟值 SIMULATED 显式标记，与测量值/推算值在数据面上可辨
 *       （模拟通道不作真实设备验证声称）；</li>
 *   <li>指令关联 commandNo：样点到达时本（场站, 资源）的生效指令；无生效指令为 null；
 *       关联 ≠ 可用 —— 采样时刻早于指令发送的样点不得充当本指令达标证明
 *       （见 {@link #isEvidenceEligible(long)}）。</li>
 * </ul>
 *
 * 样点不可变；质量标记与关联在入库时一次定型。
 */
public final class MonitoredSample {

    private final String stationId;
    private final String resourceId;
    private final long seq;
    private final long sampledAtMs;
    private final long receivedAtMs;
    private final double powerKw;
    private final TelemetryDataSource source;
    private final Set<SampleQualityMark> qualityMarks;
    /** 入库时的生效指令编号；无生效指令为 null（关联判定的输入，不是达标结论） */
    private final String commandNo;

    MonitoredSample(String stationId, String resourceId, long seq,
                    long sampledAtMs, long receivedAtMs, double powerKw,
                    TelemetryDataSource source, Set<SampleQualityMark> qualityMarks,
                    String commandNo) {
        this.stationId = Objects.requireNonNull(stationId, "场站标识不能为空");
        this.resourceId = Objects.requireNonNull(resourceId, "资源标识不能为空");
        this.seq = seq;
        this.sampledAtMs = sampledAtMs;
        this.receivedAtMs = receivedAtMs;
        this.powerKw = powerKw;
        this.source = Objects.requireNonNull(source, "数据来源不能为空");
        this.qualityMarks = qualityMarks == null || qualityMarks.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(qualityMarks));
        this.commandNo = commandNo;
    }

    /**
     * 是否可作本指令（发送时刻 sentAtMs）的达标证据：
     * 已关联指令、非重复、非迟到，且采样时刻不早于指令发送时刻。
     * 乱序样点（仅 OUT_OF_ORDER 标记）排序后仍可用；指令之前的样点、
     * 重复副本、迟到样点一律排除 —— 无证据不标达标。
     */
    public boolean isEvidenceEligible(long sentAtMs) {
        return commandNo != null
                && !qualityMarks.contains(SampleQualityMark.DUPLICATE)
                && !qualityMarks.contains(SampleQualityMark.LATE)
                && sampledAtMs >= sentAtMs;
    }

    /** 是否采样于指令发送之前（指令之前的样点：可入曲线，不作本指令达标证明） */
    public boolean isBeforeCommandSent(long sentAtMs) {
        return commandNo != null && sampledAtMs < sentAtMs;
    }

    public String getStationId() {
        return stationId;
    }

    public String getResourceId() {
        return resourceId;
    }

    /** 报文完整性序列号（44260 第 5.4 条数据校核的去重基准） */
    public long getSeq() {
        return seq;
    }

    /** 采样时标（设备侧，毫秒）—— 曲线横轴与达标判定基准 */
    public long getSampledAtMs() {
        return sampledAtMs;
    }

    /** 接收时标（平台侧，毫秒）—— 迟到判定基准 */
    public long getReceivedAtMs() {
        return receivedAtMs;
    }

    /** 实际功率（kW）；有效零值与缺失分列 —— 缺失不补 0，真零值是正常样点 */
    public double getPowerKw() {
        return powerKw;
    }

    /** 数据来源（模拟值 SIMULATED / 测量值 MEASURED / 推算值 ESTIMATED） */
    public TelemetryDataSource getSource() {
        return source;
    }

    /** 监测方质量标记集合：正常样点为空集，不返回 null */
    public Set<SampleQualityMark> getQualityMarks() {
        return qualityMarks;
    }

    /** 入库时的生效指令编号；无生效指令为 null */
    public String getCommandNo() {
        return commandNo;
    }

    @Override
    public String toString() {
        return "MonitoredSample{station=" + stationId
                + ", resource=" + resourceId
                + ", seq=" + seq
                + ", sampledAtMs=" + sampledAtMs
                + ", receivedAtMs=" + receivedAtMs
                + ", powerKw=" + powerKw
                + ", source=" + source
                + ", qualityMarks=" + qualityMarks
                + ", commandNo=" + commandNo
                + '}';
    }
}
