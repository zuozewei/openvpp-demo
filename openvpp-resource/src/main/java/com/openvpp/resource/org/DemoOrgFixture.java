package com.openvpp.resource.org;

import java.util.List;

/**
 * 示例数据装配结果 —— DemoOrgDataInitializer 返回的 ID 清单快照（不可变）。
 * 登录演示（账号-场站绑定）与后续申报、分账环节按本清单取稳定的业务 ID，
 * 避免各处硬编码分散漂移。
 */
public final class DemoOrgFixture {

    private final List<String> tenantIds;
    private final List<String> entityIds;
    private final List<String> stationIds;
    private final List<String> resourceIds;

    DemoOrgFixture(List<String> tenantIds, List<String> entityIds,
                   List<String> stationIds, List<String> resourceIds) {
        this.tenantIds = List.copyOf(tenantIds);
        this.entityIds = List.copyOf(entityIds);
        this.stationIds = List.copyOf(stationIds);
        this.resourceIds = List.copyOf(resourceIds);
    }

    public List<String> getTenantIds() {
        return tenantIds;
    }

    public List<String> getEntityIds() {
        return entityIds;
    }

    public List<String> getStationIds() {
        return stationIds;
    }

    public List<String> getResourceIds() {
        return resourceIds;
    }
}
