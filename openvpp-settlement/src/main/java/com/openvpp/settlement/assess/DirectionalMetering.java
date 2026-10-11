package com.openvpp.settlement.assess;

import com.openvpp.market.charge.DrDirection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 方向性计量 —— 核定响应电量的唯一计算入口（第 56 篇底座）。
 *
 * 口径（3.5.2 核算口径段 + 3.5.5"基线与评估"格）：
 * <ol>
 *   <li>只按有效区间计量：入窗（响应窗口内）且质量门通过的有效样点参与积分；
 *       重复/迟到/指令之前的样点一律剔除，缺失不补 0（缺口只进缺失计数），
 *       有效零值（真实零功率）照常计入；</li>
 *   <li>按采样区间积分（非均匀采样的通用核算）：样点按采样时标排序，
 *       功率按阶梯保持到下一有效样点 —— 第 i 点权重 = t[i+1] − t[i]，
 *       绝不用"平均值 × 全事件时长"充当通用核算（该形态只配出现在看板估算列）；</li>
 *   <li>方向分列：削峰 Σ max(基线 − 实际, 0) × Δt，填谷 Σ max(实际 − 基线, 0) × Δt，
 *       只累计有效方向的正偏差 —— 反向偏差不倒扣、超发不倒扣；</li>
 *   <li>计量区间 = [首有效样点, 末有效样点]：末点之后到窗口结束无证据，
 *       不计入（不把无数据时段当零响应，也不当响应延续）。</li>
 * </ol>
 *
 * 基线只读冻结版本：基线水平由评估规则从冻结样本推导（规则适配入口），
 * 本类不接触任何未冻结的基线数据。
 */
public class DirectionalMetering {

    /** 毫秒 → 小时换算 */
    private static final double MS_TO_HOURS = 1.0 / 3_600_000.0;

    /**
     * 方向性计量（只读冻结基线）。
     *
     * @param freeze         已冻结基线（方向/窗口/样本随记录留痕，计量的唯一参照）
     * @param samples        响应期监测样点（含各质量形态，窗内外混合均可传入）
     * @param missingSamples 响应窗内缺失样点数（序号缺口合计，不补 0）
     * @param rule           评估规则（基线水平推导 + 完整度口径）
     */
    public MeteringOutcome meter(BaselineFreeze freeze, List<MeteredSample> samples,
                                 int missingSamples, AssessmentRule rule) {
        long windowStartMs = freeze.windowStartMs();
        long windowEndMs = freeze.windowEndMs();
        double baselineKw = rule.baselineLevelKw(freeze);

        List<MeteredSample> eligible = new ArrayList<>();
        int excluded = 0;
        for (MeteredSample sample : samples) {
            if (sample.getSampledAtMs() < windowStartMs || sample.getSampledAtMs() > windowEndMs) {
                continue;   // 窗外样点：基线期样点/窗口边界外样点，不作计量证据
            }
            if (sample.isEffective()) {
                eligible.add(sample);
            } else {
                excluded++; // 窗内剔除样点：重复/迟到/指令前，入剔除计数不作证据
            }
        }
        eligible.sort(Comparator.comparingLong(MeteredSample::getSampledAtMs));

        // 阶梯积分：第 i 点保持到第 i+1 点；末点无后继区间，只作证据锚点
        double confirmedKwh = 0;
        for (int i = 0; i + 1 < eligible.size(); i++) {
            long intervalMs = eligible.get(i + 1).getSampledAtMs() - eligible.get(i).getSampledAtMs();
            if (intervalMs <= 0) {
                continue;   // 同刻样点（去重后的残余），不形成区间
            }
            double deviationKw = directionalDeviation(freeze.getDirection(),
                    baselineKw, eligible.get(i).getPowerKw());
            if (deviationKw > 0) {
                confirmedKwh += deviationKw * intervalMs * MS_TO_HOURS;
            }
        }

        // 看板估算（不作核定依据）：有效样点方向偏差均值 × 全窗口时长
        double dashboardEstimateKwh = 0;
        if (!eligible.isEmpty()) {
            double sum = 0;
            for (MeteredSample sample : eligible) {
                sum += Math.max(directionalDeviation(freeze.getDirection(),
                        baselineKw, sample.getPowerKw()), 0);
            }
            double windowHours = (windowEndMs - windowStartMs) * MS_TO_HOURS;
            dashboardEstimateKwh = sum / eligible.size() * windowHours;
        }

        int eligibleCount = eligible.size();
        int expected = eligibleCount + missingSamples;
        double completeness = expected == 0 ? 0 : (double) eligibleCount / expected;
        long meteringStartMs = eligibleCount == 0 ? windowStartMs : eligible.get(0).getSampledAtMs();
        long meteringEndMs = eligibleCount == 0 ? windowStartMs : eligible.get(eligibleCount - 1).getSampledAtMs();
        return new MeteringOutcome(confirmedKwh, dashboardEstimateKwh,
                meteringStartMs, meteringEndMs, eligibleCount, excluded,
                missingSamples, completeness);
    }

    /**
     * 方向性偏差（kW）：削峰 = 基线 − 实际（压下来为正），填谷 = 实际 − 基线（抬上去为正）。
     */
    static double directionalDeviation(DrDirection direction, double baselineKw, double actualKw) {
        return direction == DrDirection.PEAK_SHAVE
                ? baselineKw - actualKw
                : actualKw - baselineKw;
    }
}
