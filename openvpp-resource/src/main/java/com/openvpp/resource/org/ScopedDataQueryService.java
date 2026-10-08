package com.openvpp.resource.org;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.enums.ResourceType;
import com.openvpp.resource.ledger.ResourceLedgerService;
import com.openvpp.resource.profile.ResourceProfile;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据范围查询服务 —— 唯一接受 DataScope 的场站/资源查询入口（第 52 篇底座的执行落点）。
 *
 * 过滤规则（与 DataScopeResolver 配套，本服务不得自行放宽）：
 * 1. scope 为空范围（isEmpty()）：直接短路返回空结果 —— 禁止退化为全量查询；
 * 2. tenantWide：可见范围 = 锚定租户内全部场站（平台角色仅限本租户，不得跨租户）；
 * 3. 其余形态：可见范围 = stationIds 显式清单（以登记在册为限）—— 清单外的不可见；
 *    清单的租户约束力由身份侧保证（运营商/场站角色的清单由账号绑定层限定本租户，
 *    跨租户清单仅 SYSTEM_TASK 身份经显式声明可得，见 DataScopeResolver）。
 *
 * 资源可见性经"资源 → 场站"归属键（ResourceProfile.stationId）判定：
 * 未挂靠场站的资源在按范围查询中不可见（无法证明归属即不可见）。
 */
public class ScopedDataQueryService {

    private final OrgRegistryService registry;
    private final ResourceLedgerService ledger;

    public ScopedDataQueryService(OrgRegistryService registry, ResourceLedgerService ledger) {
        this.registry = registry;
        this.ledger = ledger;
    }

    /** 按数据范围查场站；空范围短路返回空结果 */
    public List<Station> queryStations(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        if (scope.isTenantWide()) {
            return registry.listStations(scope.getTenantId());
        }
        return registry.listStations(scope.getStationIds());
    }

    /** 按数据范围查全部类型资源 */
    public List<ResourceProfile> queryResources(DataScope scope) {
        return queryResources(scope, null);
    }

    /**
     * 按数据范围 + 资源类型查资源。
     * 可见性判定：资源挂靠的场站必须在范围内（未挂靠场站的资源不可见）。
     */
    public List<ResourceProfile> queryResources(DataScope scope, ResourceType type) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        Set<String> visibleStationIds = queryStations(scope).stream()
                .map(Station::getStationId)
                .collect(Collectors.toSet());
        if (visibleStationIds.isEmpty()) {
            return List.of();
        }
        return ledger.query(type, null).stream()
                .filter(p -> p.getStationId() != null && visibleStationIds.contains(p.getStationId()))
                .collect(Collectors.toList());
    }

    /**
     * 归属链解析：资源 → 场站 → 运营主体 → 租户，四级逐级 require。
     *
     * @throws IllegalArgumentException 资源未建档 / 场站未登记 / 主体未登记 / 租户未登记
     * @throws IllegalStateException    资源档案缺少场站归属键
     */
    public OwnershipChain chainOf(String resourceId) {
        ResourceProfile resource = ledger.require(resourceId);
        if (resource.getStationId() == null) {
            throw new IllegalStateException("资源档案缺少场站归属键，无法解析归属链: " + resourceId);
        }
        Station station = registry.requireStation(resource.getStationId());
        OperatingEntity entity = registry.requireEntity(station.getEntityId());
        Tenant tenant = registry.requireTenant(entity.getTenantId());
        return new OwnershipChain(resourceId, station.getStationId(),
                entity.getEntityId(), tenant.getTenantId());
    }
}
