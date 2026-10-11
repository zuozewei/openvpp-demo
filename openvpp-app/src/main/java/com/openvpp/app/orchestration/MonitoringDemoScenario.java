package com.openvpp.app.orchestration;

import com.openvpp.iot.simulate.FaultInjectionProfile;

import java.util.Collections;
import java.util.Set;

/**
 * 执行监测演示场景 —— 第 55 篇故障注入的教学剧本（3.5.5"监测与质量"验收格场景化）。
 *
 * 每个场景把 {@link FaultInjectionProfile} 的一种注入映射成一条可 curl 复跑的链路：
 * 计划确认 → 指令下发 → 模拟遥测回流 → 监测评估与告警。同一档案 + 同一种子下
 * 通道产出确定（iot 模块口径），判定结论（达标/超差/核查）可对拍。
 */
public enum MonitoringDemoScenario {

    /** 健康设备：围绕目标波动 ±2 kW，全链路达标（开箱基线） */
    HEALTHY("健康设备全链路达标"),

    /** 削峰压不下来：实测 = 目标 + 波动 + 10 kW（系统性偏高，削峰方向超差告警） */
    PEAK_BIAS("削峰方向系统性偏高"),

    /** 填谷抬不上去：实测 = 目标 + 波动 − 10 kW（系统性偏低，填谷方向超差告警） */
    VALLEY_BIAS("填谷方向系统性偏低"),

    /** 零目标仍有实际功率：停机目标下实测约 8 kW —— 绝对偏差口径，比例偏差会掩盖 */
    ZERO_TARGET("零目标但有实际功率"),

    /** 遥测迟到：接收时标 = 采样时标 + 60 s（超过监测阈值 30 s，不充当达标证明） */
    LATE_STREAM("遥测整体迟到"),

    /** 序号缺口：序号 5、6 缺失（不补 0，曲线留缺并登记缺口） */
    MISSING_POINTS("遥测序号缺口"),

    /** 重复样点：每 3 个样点注入 1 个副本（按 序号+采样时标 去重，不作证据） */
    DUPLICATE_POINTS("遥测重复样点"),

    /** 乱序到达：相邻样点两两交换发出顺序（按采样时标重排，识别不误判） */
    OUT_OF_ORDER_POINTS("遥测乱序到达"),

    /** 失联：下行无回执 + 遥测断流（进入核查状态，不直接断言设备未执行） */
    OFFLINE("设备失联进入核查");

    private final String description;

    MonitoringDemoScenario(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** 本场景的故障注入档案（确定性可复现） */
    public FaultInjectionProfile profile() {
        switch (this) {
            case PEAK_BIAS:
                return new FaultInjectionProfile(false, 0, 0L, Collections.emptySet(), false, 10.0);
            case VALLEY_BIAS:
                return new FaultInjectionProfile(false, 0, 0L, Collections.emptySet(), false, -10.0);
            case ZERO_TARGET:
                return new FaultInjectionProfile(false, 0, 0L, Collections.emptySet(), false, 8.0);
            case LATE_STREAM:
                return new FaultInjectionProfile(false, 0, 60_000L, Collections.emptySet(), false, 0.0);
            case MISSING_POINTS:
                return new FaultInjectionProfile(false, 0, 0L, Set.of(5L, 6L), false, 0.0);
            case DUPLICATE_POINTS:
                return new FaultInjectionProfile(false, 3, 0L, Collections.emptySet(), false, 0.0);
            case OUT_OF_ORDER_POINTS:
                return new FaultInjectionProfile(false, 0, 0L, Collections.emptySet(), true, 0.0);
            case OFFLINE:
                return new FaultInjectionProfile(true, 0, 0L, Collections.emptySet(), false, 0.0);
            case HEALTHY:
            default:
                return FaultInjectionProfile.none();
        }
    }

    /** 场景缺省方向：填谷场景固定填谷，其余默认削峰（请求可显式覆盖） */
    public com.openvpp.market.charge.DrDirection defaultDirection() {
        return this == VALLEY_BIAS
                ? com.openvpp.market.charge.DrDirection.VALLEY_FILL
                : com.openvpp.market.charge.DrDirection.PEAK_SHAVE;
    }
}
