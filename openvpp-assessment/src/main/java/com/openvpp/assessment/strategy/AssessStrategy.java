package com.openvpp.assessment.strategy;

import com.openvpp.assessment.capability.ResourceCapability;
import com.openvpp.common.enums.Scenario;

/**
 * 评估策略 —— 加权评分扩展，**非标准方法**。
 * 44260 的综合评估路径（第 6.4 条 + 附录 A.3）是"场景选指标 → 逐项对设定值判定 → 经济指标比选"，
 * 标准给的是场景与指标对应关系（表 A.1）和设定值达成判据（表 A.2），没有加权评分法；
 * 本接口只用于同场景内的资源排序与择优推荐，不能替代达标门禁。
 * 调峰场景：调节容量权重高，响应时间权重低；
 * 调频场景：响应时间、偏差率权重高；
 * 备用场景：持续时间、调节容量权重高。
 * 权重是运营 know-how，标准不给，做成配置项不写死（当前仅有接口，无实现）。
 */
public interface AssessStrategy {

    /**
     * 按场景计算单项指标得分
     *
     * @param capability 资源能力评估结果（七指标 + 置信度）
     * @param scenario   目标应用场景
     * @return 0-100 加权综合得分
     */
    double score(ResourceCapability capability, Scenario scenario);
}
