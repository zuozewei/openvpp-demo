package com.openvpp.dispatch.monitor;

import java.util.List;

/**
 * 执行评估 —— 单桩指令的监测结论（第 55 篇底座）。
 *
 * "回执 ≠ 到位 ≠ 连续达标"三件事分列，互不推导：
 * <ul>
 *   <li>receiptReceived：收到设备回执（受理/拒绝皆算；回执只证明设备应答）；</li>
 *   <li>attained：到位 —— 指令发送后存在容差带内的有效样点（单点到位）；</li>
 *   <li>steadyAttained：连续达标 —— 按采样时标排序后连续 N 个有效样点落在容差带内。</li>
 * </ul>
 *
 * 另两列：checkStatus（失联进入核查，不直接断言设备未执行）与
 * deviation（方向性偏差/零目标绝对偏差，无证据时为 null）。
 * 达标与偏差只基于指令发送后的有效样点 —— 指令之前的样点、重复副本、
 * 迟到样点一律不作证据（无证据不标完成）。
 */
public final class ExecutionEvaluation {

    private final CommandMonitorLink link;
    private final boolean receiptReceived;
    private final boolean attained;
    private final boolean steadyAttained;
    private final ExecutionCheckStatus checkStatus;
    /** 方向性偏差结论；无有效证据或指令被拒时为 null（不下结论） */
    private final DirectionalDeviation deviation;
    /** 本指令当前未确认/已确认的告警记录（按产生时标升序） */
    private final List<MonitorAlert> alerts;

    ExecutionEvaluation(CommandMonitorLink link, boolean receiptReceived, boolean attained,
                        boolean steadyAttained, ExecutionCheckStatus checkStatus,
                        DirectionalDeviation deviation, List<MonitorAlert> alerts) {
        this.link = link;
        this.receiptReceived = receiptReceived;
        this.attained = attained;
        this.steadyAttained = steadyAttained;
        this.checkStatus = checkStatus;
        this.deviation = deviation;
        this.alerts = List.copyOf(alerts);
    }

    public CommandMonitorLink getLink() {
        return link;
    }

    /** 回执环：是否收到设备回执（受理/拒绝皆算；回执 ≠ 到位） */
    public boolean isReceiptReceived() {
        return receiptReceived;
    }

    /** 到位环：指令发送后存在容差带内有效样点（单点到位；≠ 回执、≠ 连续达标） */
    public boolean isAttained() {
        return attained;
    }

    /** 连续达标环：连续 N 个有效样点落在容差带内（N 为装配参数，教学默认 3） */
    public boolean isSteadyAttained() {
        return steadyAttained;
    }

    /** 核查状态：失联（无回执）进入 OFFLINE_INVESTIGATION，不直接断言设备未执行 */
    public ExecutionCheckStatus getCheckStatus() {
        return checkStatus;
    }

    public DirectionalDeviation getDeviation() {
        return deviation;
    }

    public List<MonitorAlert> getAlerts() {
        return alerts;
    }
}
