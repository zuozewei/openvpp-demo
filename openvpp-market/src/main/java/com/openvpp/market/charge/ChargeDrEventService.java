package com.openvpp.market.charge;

import com.openvpp.common.context.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 充电桩需求响应事件服务 —— 事件的建立/发布/授权/修订/结束与按数据范围查询（第 53 篇底座）。
 *
 * 硬规则：
 * 1. 时间链：建立时要求 建立时刻 < 申报截止 < 窗口开始 < 窗口结束，事件不得"带病出生"；
 * 2. 参与授权：共享事件仅对显式授权场站可见 —— 授权只放行事件头，
 *    不放开关联租户业务数据（申报/计划/执行明细仍按各自 DataScope 查询）；
 * 3. 事件版本：参与授权、目标修订均递增版本，版本留痕供追溯；
 * 4. 数据范围：事件查询接受 DataScope —— 平台看本租户事件，
 *    运营商/场站经授权清单看共享事件（未发布事件对外不可见），
 *    空范围短路返回空结果，禁止退化为全量查询。
 *
 * 角色约束（仅平台组织方可建立/发布/授权/修订/结束）由 controller 层 @RequireRole 执行，
 * 本服务以 DataScope 做租户级防御：操作方必须 tenantWide 且锚定事件组织方租户。
 */
public class ChargeDrEventService {

    private static final Logger log = LoggerFactory.getLogger(ChargeDrEventService.class);

    private final Map<String, ChargeDrEvent> events = new ConcurrentHashMap<>();

    /**
     * 建立事件（CREATED）。结构性校验在事件构造内完成，此处校验时间链与唯一性。
     */
    public ChargeDrEvent create(String eventId, String organizerTenantId, DrDirection direction,
                                LocalDateTime windowStart, LocalDateTime windowEnd,
                                BigDecimal targetAdjustKw, LocalDateTime declareDeadline,
                                LocalDateTime at) {
        Objects.requireNonNull(at, "建立时刻 at 不能为空");
        if (!declareDeadline.isAfter(at)) {
            throw new IllegalStateException("申报截止时刻必须晚于建立时刻: " + declareDeadline);
        }
        ChargeDrEvent event = new ChargeDrEvent(eventId, organizerTenantId, direction,
                windowStart, windowEnd, targetAdjustKw, declareDeadline);
        if (events.putIfAbsent(eventId, event) != null) {
            throw new IllegalStateException("事件已存在: " + eventId);
        }
        log.info("事件建立: {} 组织方 {} {} {} ~ {} 目标 {} kW 截止 {}",
                eventId, organizerTenantId, direction.getLabel(), windowStart, windowEnd,
                targetAdjustKw, declareDeadline);
        return event;
    }

    /** 发布事件：CREATED → PUBLISHED；仅组织方租户范围可执行 */
    public ChargeDrEvent publish(DataScope callerScope, String eventId, LocalDateTime at) {
        ChargeDrEvent event = requireOrganizerScope(callerScope, eventId);
        if (!at.isBefore(event.getDeclareDeadline())) {
            throw new IllegalStateException("申报截止已过，事件不得发布: " + eventId);
        }
        event.transitLifecycle(EventLifecycle.PUBLISHED);
        log.info("事件发布: {} 版本 {} 截止 {}", eventId, event.getVersion(), event.getDeclareDeadline());
        return event;
    }

    /**
     * 参与授权：把场站纳入事件申报范围（显式授权，可批量）。
     * 仅 PUBLISHED 且未过截止时刻的事件可变更参与范围；每次授权递增事件版本。
     */
    public ChargeDrEvent authorizeParticipation(DataScope callerScope, String eventId,
                                                Collection<String> stationIds, String authorizedBy,
                                                LocalDateTime at) {
        ChargeDrEvent event = requireOrganizerScope(callerScope, eventId);
        if (event.getLifecycle() != EventLifecycle.PUBLISHED) {
            throw new IllegalStateException("仅已发布事件可变更参与范围: " + eventId);
        }
        if (!at.isBefore(event.getDeclareDeadline())) {
            throw new IllegalStateException("已过申报截止时刻，不得变更参与范围: " + eventId);
        }
        if (stationIds == null || stationIds.isEmpty()) {
            throw new IllegalArgumentException("参与授权场站清单不能为空");
        }
        for (String stationId : stationIds) {
            if (stationId == null || stationId.isBlank()) {
                throw new IllegalArgumentException("参与授权场站标识不能为空");
            }
            event.registerParticipation(new EventParticipation(stationId, authorizedBy, at));
        }
        event.bumpVersion();
        log.info("事件参与授权: {} 版本 {} 场站 [{}] 授权人 {}", eventId, event.getVersion(),
                String.join(",", stationIds), authorizedBy);
        return event;
    }

    /** 目标调节量修订：仅 PUBLISHED 且未过截止时刻；修订递增事件版本 */
    public ChargeDrEvent reviseTarget(DataScope callerScope, String eventId,
                                      BigDecimal newTargetAdjustKw, LocalDateTime at) {
        ChargeDrEvent event = requireOrganizerScope(callerScope, eventId);
        if (event.getLifecycle() != EventLifecycle.PUBLISHED) {
            throw new IllegalStateException("仅已发布事件可修订目标: " + eventId);
        }
        if (!at.isBefore(event.getDeclareDeadline())) {
            throw new IllegalStateException("已过申报截止时刻，不得修订目标: " + eventId);
        }
        event.reviseTarget(newTargetAdjustKw);
        event.bumpVersion();
        log.info("事件目标修订: {} 版本 {} 新目标 {} kW", eventId, event.getVersion(), newTargetAdjustKw);
        return event;
    }

    /** 结束事件：PUBLISHED → ENDED；仅组织方租户范围可执行 */
    public ChargeDrEvent end(DataScope callerScope, String eventId, LocalDateTime at) {
        ChargeDrEvent event = requireOrganizerScope(callerScope, eventId);
        event.transitLifecycle(EventLifecycle.ENDED);
        log.warn("事件结束: {} 版本 {} 操作时刻 {}", eventId, event.getVersion(), at);
        return event;
    }

    public ChargeDrEvent require(String eventId) {
        ChargeDrEvent event = events.get(eventId);
        if (event == null) {
            throw new IllegalArgumentException("未登记事件: " + eventId);
        }
        return event;
    }

    /**
     * 按数据范围查事件：
     * 1. 空范围短路返回空结果（禁止退化为全量查询）；
     * 2. tenantWide（平台组织方）：仅本租户发起的事件；
     * 3. 其余形态（运营商/场站/服务任务）：仅已发布或已结束、且参与授权覆盖范围内场站的事件。
     */
    public List<ChargeDrEvent> queryEvents(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        if (scope.isTenantWide()) {
            return events.values().stream()
                    .filter(e -> scope.getTenantId().equals(e.getOrganizerTenantId()))
                    .sorted(Comparator.comparing(ChargeDrEvent::getEventId))
                    .collect(Collectors.toList());
        }
        return events.values().stream()
                .filter(e -> e.getLifecycle() != EventLifecycle.CREATED)
                .filter(e -> e.authorizedStationIds().stream().anyMatch(scope.getStationIds()::contains))
                .sorted(Comparator.comparing(ChargeDrEvent::getEventId))
                .collect(Collectors.toList());
    }

    /**
     * 组织方范围守卫：操作必须来自 tenantWide 范围且锚定事件组织方租户，
     * 否则一律拒绝（空范围禁止退化，场站清单形态不得管理平台事件）。
     */
    private ChargeDrEvent requireOrganizerScope(DataScope callerScope, String eventId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        ChargeDrEvent event = require(eventId);
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止操作事件，禁止退化为全量操作: " + eventId);
        }
        if (!callerScope.isTenantWide()) {
            throw new IllegalStateException("仅平台组织方可执行本操作（当前范围为场站清单形态）: " + eventId);
        }
        if (!callerScope.getTenantId().equals(event.getOrganizerTenantId())) {
            throw new IllegalStateException("仅事件组织方租户可执行本操作: " + eventId
                    + "（操作租户 " + callerScope.getTenantId() + "，组织方租户 " + event.getOrganizerTenantId() + "）");
        }
        return event;
    }
}
