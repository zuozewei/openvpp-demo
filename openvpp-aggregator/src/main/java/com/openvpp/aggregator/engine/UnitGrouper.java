package com.openvpp.aggregator.engine;

import com.openvpp.aggregator.unit.AdmissionThreshold;
import com.openvpp.aggregator.unit.VppUnit;
import com.openvpp.common.enums.Scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单元分组器 —— 按 47241 第 11.3 条把资源聚成 VPP 单元。
 * 分组约定：
 * 1. 示例工程按出清节点分组（同节点便于统一结算；标准允许在电网条件和市场规则允许时跨节点聚合）；
 * 2. 代理期内才准入：仅消费上游传入的 contractValid 标记，**本类不从 contractEnd 复算到期日**
 *    （调度前复查待补，见专栏第 02 篇追踪表 5.2 行）;
 * 3. 单元调节容量宜 ≥1MW 准入门槛（AdmissionThreshold）；不达标的单元加 -below-threshold 后缀保留，不是拒绝。
 */
public class UnitGrouper {

    /** 未达准入门槛单元的标识后缀（保留单元供诊断，不做删除）。 */
    public static final String BELOW_THRESHOLD_SUFFIX = "-below-threshold";

    /**
     * 按出清节点分组，每组生成一个 VppUnit；不达准入门槛的单元被标记但保留（供诊断）。
     */
    public List<VppUnit> group(List<AssessedResource> pool, Scenario scenario) {
        return pool.stream()
                .filter(AssessedResource::isContractValid)
                .collect(Collectors.groupingBy(AssessedResource::getClearingNodeId))
                .entrySet().stream()
                .map(e -> buildUnit(e.getKey(), e.getValue(), scenario))
                .collect(Collectors.toList());
    }

    private VppUnit buildUnit(String nodeId, List<AssessedResource> members, Scenario scenario) {
        VppUnit unit = new VppUnit();
        unit.setUnitId("unit-" + nodeId.replaceAll("[^A-Za-z0-9]", "-"));
        unit.setClearingNodeId(nodeId);
        unit.setMarketScenario(scenario);
        unit.setResourceIds(members.stream()
                .map(AssessedResource::getResourceId)
                .collect(Collectors.toList()));

        BigDecimal unitAdjustKw = members.stream()
                .map(AssessedResource::getAdjustCapacityKw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 准入校验挂在单元上，申报前统一检查
        if (!unit.passAdmission(unitAdjustKw)) {
            unit.setUnitId(unit.getUnitId() + BELOW_THRESHOLD_SUFFIX);
        }
        return unit;
    }
}
