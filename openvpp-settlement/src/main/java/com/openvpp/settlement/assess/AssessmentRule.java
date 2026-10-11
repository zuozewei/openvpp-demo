package com.openvpp.settlement.assess;

import java.util.List;

/**
 * 效果评估规则 —— 版本化规则对象，评估追溯的锚点（第 56 篇底座）。
 *
 * 沿用第 17 篇教学默认规则口径（相似日前数据 + 宁可不算不可算错 + 版本不回溯），
 * 补填谷方向计量与规则适配入口；规则升级只影响新事件，不回溯改写已出评估。
 *
 * 评估覆盖矩阵（3.5.5"基线与评估"格 + 3.5.2 核算口径段——未实现格子显式列明，
 * 不得把未实现能力写成已实现）：
 * <table>
 *   <tr><th>维度</th><th>本版状态</th></tr>
 *   <tr><td>缺失样点处理</td><td>已实现：缺失不补 0，完整度入结论，低于阈值转人工复核</td></tr>
 *   <tr><td>削峰/填谷方向计量</td><td>已实现：只累计有效方向正偏差，反向偏差不倒扣</td></tr>
 *   <tr><td>有效零值</td><td>已实现：真实零功率照常计入计量</td></tr>
 *   <tr><td>非均匀采样</td><td>已实现：按采样区间积分，不以均值 × 全事件时长充当核算</td></tr>
 *   <tr><td>持续性（响应后持续保持）</td><td><b>未实现</b>：本版规则不判定持续性，结论不含持续性断言</td></tr>
 *   <tr><td>反弹处理</td><td><b>未实现</b>：方向性正偏差积分 ≠ 已处理反弹，本版不做反弹识别与扣减</td></tr>
 *   <tr><td>过度响应</td><td><b>部分实现</b>：超发不倒扣不考核；"响应不足考核"属偏差考核域（第 57 篇）</td></tr>
 * </table>
 */
public final class AssessmentRule {

    private final String ruleVersion;
    private final String source;
    /** 完整度阈值：有效样点覆盖比例低于该值 → 证据不足，转人工复核 */
    private final double minCompletenessRatio;
    /** 最少有效样点数：低于该值 → 证据不足，转人工复核 */
    private final int minEffectiveSamples;

    /**
     * @param ruleVersion          规则版本号
     * @param source               规则来源说明（教学场景必须明示"教学假设"）
     * @param minCompletenessRatio 完整度阈值 [0,1]
     * @param minEffectiveSamples  最少有效样点数（≥ 1）
     */
    public AssessmentRule(String ruleVersion, String source,
                          double minCompletenessRatio, int minEffectiveSamples) {
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("规则版本号不能为空");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("规则来源说明不能为空");
        }
        if (minCompletenessRatio < 0 || minCompletenessRatio > 1) {
            throw new IllegalArgumentException("完整度阈值须在 [0,1] 区间: " + minCompletenessRatio);
        }
        if (minEffectiveSamples < 1) {
            throw new IllegalArgumentException("最少有效样点数必须 ≥ 1: " + minEffectiveSamples);
        }
        this.ruleVersion = ruleVersion;
        this.source = source;
        this.minCompletenessRatio = minCompletenessRatio;
        this.minEffectiveSamples = minEffectiveSamples;
    }

    /**
     * 教学默认规则：仅用于演示口径，不对应任何地区的真实结算细则。
     * 真实项目必须以当地电网公司发布的现行结算规则文本为准逐项替换。
     */
    public static AssessmentRule teachingDefault() {
        return new AssessmentRule("TEACH-ASSESS-2026.1",
                "教学假设规则：冻结基线均值 + 有效区间方向性积分 + 完整度阈值 + 缺证据转人工复核",
                0.9, 3);
    }

    /**
     * 规则适配入口：由冻结基线样本推导核算基线水平（kW）。
     *
     * 本版取冻结样本均值（样本来源随冻结记录留痕，可追溯）；真实项目按规则文本替换
     * （如相似日分时段曲线、节假日修正），新版本经本入口生效，不回溯已出评估。
     */
    public double baselineLevelKw(BaselineFreeze freeze) {
        List<FrozenBaselinePoint> points = freeze.getPoints();
        return points.stream().mapToDouble(FrozenBaselinePoint::getPowerKw).average().orElseThrow();
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getSource() {
        return source;
    }

    public double getMinCompletenessRatio() {
        return minCompletenessRatio;
    }

    public int getMinEffectiveSamples() {
        return minEffectiveSamples;
    }
}
