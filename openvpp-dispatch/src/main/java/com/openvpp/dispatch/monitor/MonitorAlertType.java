package com.openvpp.dispatch.monitor;

/**
 * 监测告警类型 —— 执行监测发现的异常种类（第 55 篇底座）。
 */
public enum MonitorAlertType {

    /** 方向性超差：按方向（或零目标绝对口径）判定实测偏离目标超容差，附样点证据 */
    DIRECTIONAL_DEVIATION,

    /** 失联核查：指令无回执且无遥测回流，转入人工核查 —— 是"待核查"记录，
     *  不是"设备未执行"的断言 */
    OFFLINE_INVESTIGATION
}
