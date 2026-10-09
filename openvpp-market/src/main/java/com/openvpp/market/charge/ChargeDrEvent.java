package com.openvpp.market.charge;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 充电桩需求响应事件 —— 平台组织方发起的单据载体（第 53 篇底座）。
 *
 * 记录：方向、响应时间窗、目标调节量、申报截止时刻、参与授权范围与事件版本；
 * 时间状态（待响应/响应中/已结束）由 EventTimeStatus 按时间窗推导，不随业务动作改写。
 *
 * 不变式：
 * 1. 申报截止早于响应窗口开始，窗口开始早于窗口结束 —— 事件不得"带病出生"；
 * 2. 参与范围只经显式授权登记，未授权场站不可见、不可申报；
 * 3. 参与授权与目标修订均递增事件版本，版本随申报引用的快照版本各自留痕。
 */
public class ChargeDrEvent {

    private final String eventId;
    private final String organizerTenantId;
    private final DrDirection direction;
    private final LocalDateTime windowStart;
    private final LocalDateTime windowEnd;
    private final LocalDateTime declareDeadline;
    private final LocalDateTime createdAt;

    private BigDecimal targetAdjustKw;
    private int version;
    private EventLifecycle lifecycle;
    private LocalDateTime endedAt;

    private final Map<String, EventParticipation> participations = new LinkedHashMap<>();

    public ChargeDrEvent(String eventId, String organizerTenantId, DrDirection direction,
                         LocalDateTime windowStart, LocalDateTime windowEnd,
                         BigDecimal targetAdjustKw, LocalDateTime declareDeadline) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (organizerTenantId == null || organizerTenantId.isBlank()) {
            throw new IllegalArgumentException("organizerTenantId 不能为空");
        }
        Objects.requireNonNull(direction, "direction 不能为空");
        if (windowStart == null || windowEnd == null || declareDeadline == null) {
            throw new IllegalArgumentException("响应时间窗与申报截止时刻不能为空");
        }
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("响应窗口开始必须早于结束: " + windowStart + " ~ " + windowEnd);
        }
        if (!declareDeadline.isBefore(windowStart)) {
            throw new IllegalArgumentException("申报截止时刻必须早于响应窗口开始: " + declareDeadline);
        }
        if (targetAdjustKw == null || targetAdjustKw.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("目标调节量必须为正: " + targetAdjustKw);
        }
        this.eventId = eventId;
        this.organizerTenantId = organizerTenantId;
        this.direction = direction;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.targetAdjustKw = targetAdjustKw;
        this.declareDeadline = declareDeadline;
        this.version = 1;
        this.lifecycle = EventLifecycle.CREATED;
        this.createdAt = LocalDateTime.now();
    }

    public void transitLifecycle(EventLifecycle target) {
        lifecycle.assertTransitTo(target);
        lifecycle = target;
        if (target == EventLifecycle.ENDED) {
            endedAt = LocalDateTime.now();
        }
    }

    /** 登记一条参与授权（重复授权同一场站幂等覆盖，保持登记在册） */
    public void registerParticipation(EventParticipation participation) {
        participations.put(participation.getStationId(), participation);
    }

    /** 目标调节量修订（仅正值；版本递增由服务层统一处理） */
    public void reviseTarget(BigDecimal newTargetAdjustKw) {
        if (newTargetAdjustKw == null || newTargetAdjustKw.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("目标调节量必须为正: " + newTargetAdjustKw);
        }
        this.targetAdjustKw = newTargetAdjustKw;
    }

    public void bumpVersion() {
        version++;
    }

    /** 指定时刻的时间状态（按响应时间窗推导） */
    public EventTimeStatus timeStatusAt(LocalDateTime at) {
        return EventTimeStatus.of(windowStart, windowEnd, at);
    }

    /** 当前时刻是否仍可申报：已发布且早于申报截止时刻 */
    public boolean isDeclaringOpenAt(LocalDateTime at) {
        return lifecycle == EventLifecycle.PUBLISHED && at.isBefore(declareDeadline);
    }

    public boolean isStationAuthorized(String stationId) {
        return participations.containsKey(stationId);
    }

    public Set<String> authorizedStationIds() {
        return Collections.unmodifiableSet(participations.keySet());
    }

    public Collection<EventParticipation> participationRecords() {
        return Collections.unmodifiableCollection(participations.values());
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

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public BigDecimal getTargetAdjustKw() {
        return targetAdjustKw;
    }

    public LocalDateTime getDeclareDeadline() {
        return declareDeadline;
    }

    public int getVersion() {
        return version;
    }

    public EventLifecycle getLifecycle() {
        return lifecycle;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
