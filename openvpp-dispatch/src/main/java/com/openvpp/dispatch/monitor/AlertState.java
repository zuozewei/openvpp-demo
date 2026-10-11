package com.openvpp.dispatch.monitor;

/**
 * 告警状态 —— 监测告警的人工处置状态（第 55 篇底座）。
 *
 * OPEN → CONFIRMED 单向前进；确认幂等，首条确认记录为准（确认人/时间是证据）。
 * 教学口径：告警以人工确认为收口 —— 确认后同一指令同方向同类型不再重复告警。
 */
public enum AlertState {

    /** 待处理：已产生，尚未人工确认 */
    OPEN,

    /** 已确认：人工已知悉并留痕（确认人/时间）；历史记录不删除 */
    CONFIRMED
}
