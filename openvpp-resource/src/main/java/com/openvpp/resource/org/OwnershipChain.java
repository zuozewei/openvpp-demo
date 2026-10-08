package com.openvpp.resource.org;

/**
 * 归属链 —— 资源 → 场站 → 运营主体 → 租户 的四级归属快照（不可变值对象）。
 * 效果评估、多级分账等跨主体环节按本链确定结算归属与数据归属，
 * 任何一级缺失（未登记）即拒绝解析，不允许链断仍出结果。
 */
public final class OwnershipChain {

    private final String resourceId;
    private final String stationId;
    private final String entityId;
    private final String tenantId;

    OwnershipChain(String resourceId, String stationId, String entityId, String tenantId) {
        this.resourceId = resourceId;
        this.stationId = stationId;
        this.entityId = entityId;
        this.tenantId = tenantId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getStationId() {
        return stationId;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getTenantId() {
        return tenantId;
    }

    @Override
    public String toString() {
        return "OwnershipChain{resource=" + resourceId + " → station=" + stationId
                + " → entity=" + entityId + " → tenant=" + tenantId + "}";
    }
}
