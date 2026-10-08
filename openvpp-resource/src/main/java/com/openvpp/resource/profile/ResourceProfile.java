package com.openvpp.resource.profile;

import com.openvpp.common.enums.ResourceType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资源档案 —— 对应 GB/T 44260-2024 第 5.6 条（资源基本信息收集清单）。
 * 每类资源（光伏/储能/充电桩/空调）继承此基类。
 * 档案是慢变数据：建档一次，年度更新，变更留审计。
 */
public abstract class ResourceProfile {

    /** 资源唯一标识 */
    private String resourceId;

    /** 资源类型：DG 分布式电源 / ES 储能 / FL 可调负荷 */
    private ResourceType type;

    /** 装机容量/额定功率/储能容量 (kW) */
    private BigDecimal capacity;

    /** 地理位置（省/市/台区），47241 第 6.2 条接入考量因素 */
    private String geoLocation;

    /** 电气位置（并网电压等级/馈线），聚合引擎的硬过滤条件 */
    private String electricalNode;

    /** 设备型号 */
    private String deviceModel;

    /** 所属聚合主体 */
    private Long operatorId;

    /** 归属场站（业务主体体系 Station.stationId 的引用键，第 52 篇底座）—— 数据范围查询按此键判定可见性；未挂靠场站的资源不参与按范围查询 */
    private String stationId;

    /** 用电户号/并网点标识 —— 排他性校验的业务键（47241 第 6.1 条） */
    private String gridAccountId;

    /** 代理协议到期日 —— 44260 第 5.2 条：最小时间期限宜不小于 1 个月（"宜"为推荐值，工程上按默认门槛处理） */
    private LocalDate contractEnd;

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public ResourceType getType() {
        return type;
    }

    public void setType(ResourceType type) {
        this.type = type;
    }

    public BigDecimal getCapacity() {
        return capacity;
    }

    public void setCapacity(BigDecimal capacity) {
        this.capacity = capacity;
    }

    public String getGeoLocation() {
        return geoLocation;
    }

    public void setGeoLocation(String geoLocation) {
        this.geoLocation = geoLocation;
    }

    public String getElectricalNode() {
        return electricalNode;
    }

    public void setElectricalNode(String electricalNode) {
        this.electricalNode = electricalNode;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getStationId() {
        return stationId;
    }

    public void setStationId(String stationId) {
        this.stationId = stationId;
    }

    public String getGridAccountId() {
        return gridAccountId;
    }

    public void setGridAccountId(String gridAccountId) {
        this.gridAccountId = gridAccountId;
    }

    public LocalDate getContractEnd() {
        return contractEnd;
    }

    public void setContractEnd(LocalDate contractEnd) {
        this.contractEnd = contractEnd;
    }
}
