package com.openvpp.market.charge;

/**
 * 需求响应调节方向 —— 充电桩专题（第 53 篇）事件与申报的方向口径：
 * 削峰=压低在充功率，填谷=抬升在充功率。两种方向的可用容量不同源，
 * 能力快照分列两档可信容量，申报按事件方向取对应列校验。
 */
public enum DrDirection {

    /** 削峰：降低在充车辆的充电功率 */
    PEAK_SHAVE("削峰"),

    /** 填谷：提升在充车辆的充电功率 */
    VALLEY_FILL("填谷");

    private final String label;

    DrDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
