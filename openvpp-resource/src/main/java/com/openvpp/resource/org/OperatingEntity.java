package com.openvpp.resource.org;

/**
 * 运营主体 —— 归属唯一租户，是资源与场站的直接业务归属方。
 * 对应链路三方主体中的"运营商"（OPERATOR 角色绑定的业务单位）与
 * "场站运营方"（STATION_OPERATOR 角色所属的运营主体）。
 *
 * 归属约束：tenantId 登记时校验必须落在已登记租户内，且不可跨租户迁移。
 */
public class OperatingEntity {

    /** 运营主体唯一标识 */
    private String entityId;

    /** 归属租户 */
    private String tenantId;

    /** 主体名称（虚构教学名） */
    private String entityName;

    /** 主体业态：充电运营 / 负荷聚合 / 车网互动 */
    private String entityKind;

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getEntityKind() {
        return entityKind;
    }

    public void setEntityKind(String entityKind) {
        this.entityKind = entityKind;
    }
}
