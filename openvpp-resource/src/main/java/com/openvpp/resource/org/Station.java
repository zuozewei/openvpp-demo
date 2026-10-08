package com.openvpp.resource.org;

/**
 * 场站 —— 归属唯一运营主体，是资源挂靠与数据范围判定的最小单元。
 * 平台分配运营商、运营商派单到场、场站申报响应能力，均以本档案为归属依据。
 *
 * 归属约束：登记时强制校验 tenantId 与所属运营主体的 tenantId 一致，
 * 跨租户挂靠直接拒绝 —— 归属链（资源 → 场站 → 主体 → 租户）任何一环断裂即不可登记。
 */
public class Station {

    /** 场站唯一标识 —— IdentityContext.stationIds 的绑定值 */
    private String stationId;

    /** 归属租户（与所属运营主体的 tenantId 强制一致） */
    private String tenantId;

    /** 归属运营主体 */
    private String entityId;

    /** 场站名称（虚构教学名） */
    private String stationName;

    /** 地理位置（省/市/片区） */
    private String geoLocation;

    public String getStationId() {
        return stationId;
    }

    public void setStationId(String stationId) {
        this.stationId = stationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getGeoLocation() {
        return geoLocation;
    }

    public void setGeoLocation(String geoLocation) {
        this.geoLocation = geoLocation;
    }
}
