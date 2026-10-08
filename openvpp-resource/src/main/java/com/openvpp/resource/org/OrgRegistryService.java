package com.openvpp.resource.org;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 业务主体登记服务 —— 租户 / 运营主体 / 场站三级档案的唯一权威入口（第 52 篇底座）。
 *
 * 承载三条硬规则：
 * 1. 逐级归属：运营主体必须落在已登记租户内，场站必须落在已登记主体内 —— 链断即拒；
 * 2. 归属一致：场站的 tenantId 必须与所属主体的 tenantId 一致，跨租户挂靠直接拒绝，
 *    杜绝"主体在 A 租户、场站挂到 B 租户"的数据范围逃逸路径；
 * 3. 登记不删除：档案按 ID 唯一登记，重复登记抛异常，变更走展示字段而非换归属。
 *
 * 数据可见性不在本服务判定 —— 见 ScopedDataQueryService（接受 DataScope 的查询入口）。
 */
public class OrgRegistryService {

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, OperatingEntity> entities = new ConcurrentHashMap<>();
    private final Map<String, Station> stations = new ConcurrentHashMap<>();

    /** 租户登记，tenantId 重复即拒 */
    public synchronized Tenant registerTenant(Tenant tenant) {
        Objects.requireNonNull(tenant, "租户不能为空");
        Objects.requireNonNull(tenant.getTenantId(), "tenantId 不能为空");
        if (tenants.putIfAbsent(tenant.getTenantId(), tenant) != null) {
            throw new IllegalStateException("租户已登记: " + tenant.getTenantId());
        }
        return tenant;
    }

    /** 主体登记：所属租户必须已登记，entityId 重复即拒 */
    public synchronized OperatingEntity registerEntity(OperatingEntity entity) {
        Objects.requireNonNull(entity, "运营主体不能为空");
        Objects.requireNonNull(entity.getEntityId(), "entityId 不能为空");
        requireTenant(entity.getTenantId());
        if (entities.putIfAbsent(entity.getEntityId(), entity) != null) {
            throw new IllegalStateException("运营主体已登记: " + entity.getEntityId());
        }
        return entity;
    }

    /**
     * 场站登记：所属主体必须已登记，且场站 tenantId 与主体 tenantId 强制一致
     * （跨租户挂靠拒绝）；stationId 重复即拒。
     */
    public synchronized Station registerStation(Station station) {
        Objects.requireNonNull(station, "场站不能为空");
        Objects.requireNonNull(station.getStationId(), "stationId 不能为空");
        OperatingEntity owner = requireEntity(station.getEntityId());
        if (!owner.getTenantId().equals(station.getTenantId())) {
            throw new IllegalStateException(
                    "归属不一致：场站 tenantId=" + station.getTenantId()
                            + " 与所属主体 tenantId=" + owner.getTenantId() + " 不符，跨租户挂靠被拒绝");
        }
        if (stations.putIfAbsent(station.getStationId(), station) != null) {
            throw new IllegalStateException("场站已登记: " + station.getStationId());
        }
        return station;
    }

    public Tenant requireTenant(String tenantId) {
        return Optional.ofNullable(tenants.get(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("未登记租户: " + tenantId));
    }

    public OperatingEntity requireEntity(String entityId) {
        return Optional.ofNullable(entities.get(entityId))
                .orElseThrow(() -> new IllegalArgumentException("未登记运营主体: " + entityId));
    }

    public Station requireStation(String stationId) {
        return Optional.ofNullable(stations.get(stationId))
                .orElseThrow(() -> new IllegalArgumentException("未登记场站: " + stationId));
    }

    /** 租户下全部运营主体（按 entityId 排序，保证演示输出稳定） */
    public List<OperatingEntity> listEntities(String tenantId) {
        requireTenant(tenantId);
        return entities.values().stream()
                .filter(e -> tenantId.equals(e.getTenantId()))
                .sorted(Comparator.comparing(OperatingEntity::getEntityId))
                .collect(Collectors.toList());
    }

    /** 租户下全部场站（按 stationId 排序） */
    public List<Station> listStations(String tenantId) {
        requireTenant(tenantId);
        return stations.values().stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .sorted(Comparator.comparing(Station::getStationId))
                .collect(Collectors.toList());
    }

    /**
     * 租户锚定 + 场站清单过滤（平台侧管理视图）：清单外的场站不可见，
     * 清单内属于其他租户的同样被租户锚定拦截。
     */
    public List<Station> listStations(String tenantId, Set<String> stationIds) {
        requireTenant(tenantId);
        if (stationIds == null || stationIds.isEmpty()) {
            return List.of();
        }
        return stations.values().stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .filter(s -> stationIds.contains(s.getStationId()))
                .sorted(Comparator.comparing(Station::getStationId))
                .collect(Collectors.toList());
    }

    /**
     * 显式场站清单查询（数据范围执行入口）：以登记在册为限，
     * 清单外的不可见，未登记的 ID 直接落空；不做租户交集 ——
     * 清单的租户约束力由调用方保证（运营商/场站角色由账号绑定层限定本租户，
     * 跨租户清单仅 SYSTEM_TASK 身份可得，见 DataScopeResolver）。
     */
    public List<Station> listStations(Set<String> stationIds) {
        if (stationIds == null || stationIds.isEmpty()) {
            return List.of();
        }
        return stations.values().stream()
                .filter(s -> stationIds.contains(s.getStationId()))
                .sorted(Comparator.comparing(Station::getStationId))
                .collect(Collectors.toList());
    }

    /** 主体下全部场站（按 stationId 排序） */
    public List<Station> listStationsByEntity(String entityId) {
        requireEntity(entityId);
        return stations.values().stream()
                .filter(s -> entityId.equals(s.getEntityId()))
                .sorted(Comparator.comparing(Station::getStationId))
                .collect(Collectors.toList());
    }
}
