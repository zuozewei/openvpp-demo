package com.openvpp.market.charge;

import java.time.LocalDateTime;

/**
 * 事件时间状态 —— 完全由响应时间窗推导，不独立存储：
 * 待响应（窗口未开始）→ 响应中（窗口内）→ 已结束（窗口已过）。
 * 同一时刻全体参与者读到的状态一致，是申报截止、派单、评估取数的共同时间口径。
 */
public enum EventTimeStatus {

    /** 待响应：当前时刻早于响应窗口开始 */
    PENDING,

    /** 响应中：当前时刻落在响应窗口内（含窗口起点，不含终点） */
    RESPONDING,

    /** 已结束：当前时刻不早于响应窗口终点 */
    ENDED;

    /**
     * 按时间窗推导指定时刻的时间状态。
     *
     * 边界口径：到达 windowStart 即进入响应中；到达 windowEnd 即结束（左闭右开区间），
     * 同一事件在任何调用方处口径一致。
     */
    public static EventTimeStatus of(LocalDateTime windowStart, LocalDateTime windowEnd, LocalDateTime at) {
        if (windowStart == null || windowEnd == null || at == null) {
            throw new IllegalArgumentException("响应时间窗与当前时刻不能为空");
        }
        if (at.isBefore(windowStart)) {
            return PENDING;
        }
        return at.isBefore(windowEnd) ? RESPONDING : ENDED;
    }
}
