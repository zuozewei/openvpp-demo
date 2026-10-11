package com.openvpp.dispatch.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 监测告警仓库 —— 内存版告警登记簿（第 55 篇底座）。
 *
 * 登记口径：按告警标识唯一登记；告警是处置证据，确认后冻结不删除。
 * 查询过滤维度：事件 / 组织方租户 / 场站清单 / 状态 —— 租户与场站条件是
 * 数据范围守卫的落点（调用方传 {@link com.openvpp.common.context.DataScope}
 * 解析结果，本仓库不做身份判定）。
 *
 * 教学实现为单 JVM 内存版（synchronized 串行化）；分布式部署时告警需落库
 * （标识唯一约束），口径不变。
 */
public class MonitorAlertRepository {

    private final Map<String, MonitorAlert> byAlertId = new LinkedHashMap<>();

    /** 登记告警（同标识重复登记即拒） */
    public synchronized void save(MonitorAlert alert) {
        if (byAlertId.putIfAbsent(alert.getAlertId(), alert) != null) {
            throw new IllegalStateException("告警标识已登记，禁止覆盖: " + alert.getAlertId());
        }
    }

    public synchronized Optional<MonitorAlert> find(String alertId) {
        return Optional.ofNullable(byAlertId.get(alertId));
    }

    public synchronized MonitorAlert require(String alertId) {
        MonitorAlert alert = byAlertId.get(alertId);
        if (alert == null) {
            throw new IllegalArgumentException("未登记告警: " + alertId);
        }
        return alert;
    }

    /** 全部告警（按产生时标升序，不可变视图；服务内部按指令过滤用） */
    public synchronized List<MonitorAlert> listAll() {
        List<MonitorAlert> all = new ArrayList<>(byAlertId.values());
        all.sort(Comparator.comparing(MonitorAlert::getRaisedAt)
                .thenComparing(MonitorAlert::getAlertId));
        return List.copyOf(all);
    }

    /**
     * 条件查询（各条件为 AND；null/空集合表示不限）。
     *
     * @param eventId            事件标识（可选）
     * @param organizerTenantId  组织方租户（数据范围锚定，必填）
     * @param stationIds         可见场站清单（可选；null=本租户全部场站，空集合=明确无可见数据）
     * @param state              告警状态（可选）
     */
    public synchronized List<MonitorAlert> list(String eventId, String organizerTenantId,
                                                Set<String> stationIds, AlertState state) {
        List<MonitorAlert> matched = new ArrayList<>();
        for (MonitorAlert alert : byAlertId.values()) {
            if (!alert.getOrganizerTenantId().equals(organizerTenantId)) {
                continue;
            }
            if (eventId != null && !eventId.isBlank() && !alert.getEventId().equals(eventId)) {
                continue;
            }
            if (stationIds != null && !stationIds.contains(alert.getStationId())) {
                continue;
            }
            if (state != null && alert.getState() != state) {
                continue;
            }
            matched.add(alert);
        }
        matched.sort(Comparator.comparing(MonitorAlert::getRaisedAt)
                .thenComparing(MonitorAlert::getAlertId));
        return List.copyOf(matched);
    }
}
