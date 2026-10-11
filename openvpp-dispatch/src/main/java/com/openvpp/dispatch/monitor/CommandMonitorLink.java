package com.openvpp.dispatch.monitor;

import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.market.charge.DrDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 指令监测关联 —— 一条单桩指令与其遥测样点/缺口的关联载体（第 55 篇底座）。
 *
 * 关联口径：按（场站, 资源）双键生效 —— 同桩号跨场站是两条独立关联，
 * 样点只进自己场站的关联，杜绝错关联（"监测与质量"验收格"无错关联"）。
 *
 * 关联建立于指令发送时刻（sentAtMs）：此后到达该（场站, 资源）的样点进入本关联，
 * 其中采样时刻早于 sentAtMs 的样点标记 beforeCommandSent —— 可入曲线，
 * 不作本指令达标证明。新指令下发同桩即顶替旧关联（旧关联留存可查，不再进新样点）。
 */
public final class CommandMonitorLink {

    private final String eventId;
    private final String organizerTenantId;
    private final DrDirection direction;
    private final DispatchCommand command;
    /** 指令发送时刻（epoch ms，与样点同钟）—— 达标证据的时界 */
    private final long sentAtMs;

    /** 本关联名下的样点（按接收序；含指令之前的样点） */
    private final List<MonitoredSample> samples = new ArrayList<>();
    /** 本关联存续期间登记的序号缺口（缺失不补 0，只记缺口） */
    private final List<SampleGap> gaps = new ArrayList<>();

    CommandMonitorLink(String eventId, String organizerTenantId, DrDirection direction,
                       DispatchCommand command, long sentAtMs) {
        this.eventId = requireText(eventId, "事件标识");
        this.organizerTenantId = requireText(organizerTenantId, "组织方租户");
        this.direction = Objects.requireNonNull(direction, "调节方向不能为空");
        this.command = Objects.requireNonNull(command, "指令不能为空");
        if (sentAtMs < 0) {
            throw new IllegalArgumentException("发送时刻不得为负: " + sentAtMs);
        }
        this.sentAtMs = sentAtMs;
    }

    void addSample(MonitoredSample sample) {
        samples.add(sample);
    }

    void addGap(SampleGap gap) {
        gaps.add(gap);
    }

    /** 达标证据：本关联名下采样时刻不早于发送时刻、非重复、非迟到的样点 */
    public List<MonitoredSample> evidenceSamples() {
        List<MonitoredSample> evidence = new ArrayList<>();
        for (MonitoredSample sample : samples) {
            if (sample.isEvidenceEligible(sentAtMs)) {
                evidence.add(sample);
            }
        }
        return List.copyOf(evidence);
    }

    public String getEventId() {
        return eventId;
    }

    public String getOrganizerTenantId() {
        return organizerTenantId;
    }

    public DrDirection getDirection() {
        return direction;
    }

    public DispatchCommand getCommand() {
        return command;
    }

    public long getSentAtMs() {
        return sentAtMs;
    }

    /** 本关联全部样点（按接收序，不可变视图） */
    public List<MonitoredSample> getSamples() {
        return List.copyOf(samples);
    }

    /** 本关联存续期间的序号缺口（缺失不补 0） */
    public List<SampleGap> getGaps() {
        return List.copyOf(gaps);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
