package com.openvpp.settlement.charge;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分账规则快照 —— 平台/运营商/场站三方的分配比例及其版本（第 57 篇底座，
 * 规划类 SharingRuleSnapshot，3.5.2"结算单与分账明细"行"分账规则快照"列）。
 *
 * 硬规则（3.5.2 核算口径段）：
 * 1. 比例总和必须为 1 —— 不合法一律拒绝（构造即校验，不存在"带病快照"）；
 * 2. 快照随结算单保存 —— 规则变更（新比例、新版本）只影响新单与更正单，
 *    不回溯改写已确认历史（旧单上的快照就是历史口径的证据）；
 * 3. 金额只存比例，不存金额 —— 金额在结算时按净实收现算，保证可复算。
 *
 * 快照为不可变值对象：同输入必为同快照，确定性分账的前提。
 */
public final class SharingRuleSnapshot {

    /** 教学默认规则版本：仅用于演示口径，不对应任何地区/平台真实分账规则 */
    public static final String TEACHING_RULE_VERSION = "TEACH-SHARE-2026.1";
    /** 教学默认规则来源说明（教学假设口径留痕，随快照保存） */
    public static final String TEACHING_RULE_SOURCE =
            "教学假设规则：平台/运营商/场站三方固定比例分账，比例总和为 1，尾差归并比例最大方";

    private final String ruleVersion;
    private final String source;
    private final BigDecimal platformRate;
    private final BigDecimal operatorRate;
    private final BigDecimal stationRate;

    public SharingRuleSnapshot(String ruleVersion, String source,
                               BigDecimal platformRate, BigDecimal operatorRate,
                               BigDecimal stationRate) {
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("分账规则版本不能为空");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("分账规则来源说明不能为空");
        }
        this.platformRate = requireRate(platformRate, "平台方比例");
        this.operatorRate = requireRate(operatorRate, "运营商比例");
        this.stationRate = requireRate(stationRate, "场站比例");
        BigDecimal sum = this.platformRate.add(this.operatorRate).add(this.stationRate);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("分账比例总和必须为 1: 平台 " + this.platformRate
                    + " + 运营商 " + this.operatorRate + " + 场站 " + this.stationRate
                    + " = " + sum);
        }
        this.ruleVersion = ruleVersion;
        this.source = source;
    }

    /** 教学默认版本的三方比例快照 */
    public static SharingRuleSnapshot teaching(BigDecimal platformRate, BigDecimal operatorRate,
                                               BigDecimal stationRate) {
        return new SharingRuleSnapshot(TEACHING_RULE_VERSION, TEACHING_RULE_SOURCE,
                platformRate, operatorRate, stationRate);
    }

    /** 比例校验：非空且在 [0,1] 区间 */
    private static BigDecimal requireRate(BigDecimal rate, String name) {
        if (rate == null) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + "须在 [0,1] 区间: " + rate);
        }
        return rate;
    }

    /** 枚举顺序即分账行顺序（平台 → 运营商 → 场站），尾差归并比例最大方 */
    public List<BigDecimal> orderedRates() {
        return List.of(platformRate, operatorRate, stationRate);
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getSource() {
        return source;
    }

    public BigDecimal getPlatformRate() {
        return platformRate;
    }

    public BigDecimal getOperatorRate() {
        return operatorRate;
    }

    public BigDecimal getStationRate() {
        return stationRate;
    }

    @Override
    public String toString() {
        return "SharingRuleSnapshot{ruleVersion=" + ruleVersion
                + ", platform=" + platformRate + ", operator=" + operatorRate
                + ", station=" + stationRate + '}';
    }
}
