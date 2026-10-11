package com.openvpp.settlement.assess;

/**
 * 计量样点 —— 结算域计量的中性输入（第 56 篇底座）。
 *
 * 与 dispatch 侧 MonitoredSample 的分工：MonitoredSample 是监测域对入库样点的
 * 质量判定载体（包私有构造，只能经监测入库产生）；本类是结算域评估的中性输入 ——
 * 监测域的"证据资格"结论已折算成 effective 布尔（app 装配层按第 55 篇口径映射：
 * 非重复、非迟到、采样时刻不早于指令发送），结算测试可直接构造各质量形态样点
 * 验证计量行为，不依赖监测链路。
 *
 * 口径：
 * <ul>
 *   <li>有效零值：powerKw = 0 且 effective = true 是真实零功率（如停机执行），
 *       计量照常计入，不得当缺失丢弃；</li>
 *   <li>缺失没有样点载体：缺口不构造零值样点（不补 0），由计量入口的缺失计数承接。</li>
 * </ul>
 */
public final class MeteredSample {

    /** 采样时刻（epoch ms，与冻结基线样本同钟） */
    private final long sampledAtMs;
    /** 实际功率（kW） */
    private final double powerKw;
    /** 质量门结论：true = 有效样点（可计量），false = 剔除样点（重复/迟到/指令前） */
    private final boolean effective;

    public MeteredSample(long sampledAtMs, double powerKw, boolean effective) {
        this.sampledAtMs = sampledAtMs;
        this.powerKw = powerKw;
        this.effective = effective;
    }

    /** 有效样点：质量门通过（非重复/非迟到/指令之后） */
    public static MeteredSample effective(long sampledAtMs, double powerKw) {
        return new MeteredSample(sampledAtMs, powerKw, true);
    }

    /** 剔除样点：重复 / 迟到 / 指令之前 —— 不出现在计量区间 */
    public static MeteredSample excluded(long sampledAtMs, double powerKw) {
        return new MeteredSample(sampledAtMs, powerKw, false);
    }

    public long getSampledAtMs() {
        return sampledAtMs;
    }

    public double getPowerKw() {
        return powerKw;
    }

    public boolean isEffective() {
        return effective;
    }

    @Override
    public String toString() {
        return "MeteredSample{sampledAtMs=" + sampledAtMs + ", powerKw=" + powerKw
                + ", effective=" + effective + '}';
    }
}
