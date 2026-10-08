package com.openvpp.resource.org;

/**
 * 租户 —— 业务主体体系的顶层锚点（第 52 篇充电桩需求响应运营链路的归属根）。
 * 平台按租户隔离数据：任何数据范围（DataScope）都锚定唯一 tenantId，
 * 跨租户访问仅 SYSTEM_TASK 身份经显式清单可得（见 DataScopeResolver）。
 *
 * 演示版内存档案：归属关系登记后不可变更，名称等展示字段可改。
 */
public class Tenant {

    /** 租户唯一标识 —— 数据范围锚定键 */
    private String tenantId;

    /** 租户名称（虚构教学名） */
    private String tenantName;

    /** 服务区域（省/市） */
    private String serviceRegion;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public String getServiceRegion() {
        return serviceRegion;
    }

    public void setServiceRegion(String serviceRegion) {
        this.serviceRegion = serviceRegion;
    }
}
