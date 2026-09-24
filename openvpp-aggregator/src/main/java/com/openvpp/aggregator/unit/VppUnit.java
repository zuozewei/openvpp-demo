package com.openvpp.aggregator.unit;

import com.openvpp.common.enums.Scenario;

import java.math.BigDecimal;
import java.util.List;

/**
 * VPP 单元 —— 对应 GB/T 47241-2026 第 3.6/11.3 条。
 * 按资源特性、地理位置、电气位置及市场需求对资源逻辑分组，
 * 作为基本单元参与电力交易或电网调节。
 * 分组约定：示例工程按 11.3 条选择同节点分组；
 * 标准允许在电网条件和市场规则允许时跨节点聚合。
 */
public class VppUnit {

    private String unitId;

    /** 出清节点 —— 示例工程按同节点分组，否则结算无法统一 */
    private String clearingNodeId;

    private List<String> resourceIds;

    /** 该单元申报的市场品种 */
    private Scenario marketScenario;

    /**
     * 准入校验：4.2 条单元调节容量宜 ≥1MW
     */
    public boolean passAdmission(BigDecimal totalAdjustCapacityKw) {
        return totalAdjustCapacityKw.compareTo(
                BigDecimal.valueOf(AdmissionThreshold.MIN_UNIT_ADJUST_KW)) >= 0;
    }

    public String getUnitId() {
        return unitId;
    }

    public void setUnitId(String unitId) {
        this.unitId = unitId;
    }

    public String getClearingNodeId() {
        return clearingNodeId;
    }

    public void setClearingNodeId(String clearingNodeId) {
        this.clearingNodeId = clearingNodeId;
    }

    public List<String> getResourceIds() {
        return resourceIds;
    }

    public void setResourceIds(List<String> resourceIds) {
        this.resourceIds = resourceIds;
    }

    public Scenario getMarketScenario() {
        return marketScenario;
    }

    public void setMarketScenario(Scenario marketScenario) {
        this.marketScenario = marketScenario;
    }
}
