package com.openvpp.iot.simulate;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 指令回执 —— 设备侧对"设定绝对目标功率"指令的受理回执。
 *
 * 回执只证明"设备收到并受理"，不证明"功率已到位"——到位与否只能由后续遥测样点
 * 按目标判定（与 DispatchInstruction 的 ACK/遥测分层口径一致：ACK 不是响应）。
 *
 * 三种结果必须分清（第 55 篇"设备状态与业务执行状态分离"）：
 *   ACCEPTED        在线且受理：目标已写入设备生效，等遥测证明到位；
 *   REJECTED        在线但拒绝：带机器可读原因码（可重决策），目标未变；
 *   无回执（Optional.empty）    失联：指令可能根本没到设备，不得当拒绝处理，
 *                   调用方应走超时核查而不是重发（防设备重复动作）。
 *
 * 回执携带目标功率回显与回执时标，构成第 54 篇"下发证据"链的回执环：
 * 计划确认、发送、回执、遥测达标分列，无证据不标完成。
 */
public class CommandReceipt {

    /** 受理结果 */
    public enum Status {
        /** 已受理：目标写入设备生效 */
        ACCEPTED,
        /** 已拒绝：原因见 {@link #getReason()}，目标未变 */
        REJECTED
    }

    private final String commandNo;
    private final String resourceId;
    private final BigDecimal targetKw;
    private final Status status;
    /** 拒绝原因码（机器可读，如 TARGET_OUT_OF_RANGE）；受理时为 null */
    private final String reason;
    /** 回执时标（设备侧受理时刻，毫秒） */
    private final long ackedAtMs;

    public CommandReceipt(String commandNo, String resourceId, BigDecimal targetKw,
                          Status status, String reason, long ackedAtMs) {
        this.commandNo = Objects.requireNonNull(commandNo, "commandNo");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.targetKw = Objects.requireNonNull(targetKw, "targetKw");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = reason;
        this.ackedAtMs = ackedAtMs;
    }

    /** 业务指令编号（设备侧按它去重，重试须换新编号） */
    public String getCommandNo() {
        return commandNo;
    }

    /** 资源标识（桩号） */
    public String getResourceId() {
        return resourceId;
    }

    /** 目标功率回显（kW，绝对功率目标，非调节量） */
    public BigDecimal getTargetKw() {
        return targetKw;
    }

    public Status getStatus() {
        return status;
    }

    /** 拒绝原因码；受理时为 null */
    public String getReason() {
        return reason;
    }

    /** 回执时标（毫秒） */
    public long getAckedAtMs() {
        return ackedAtMs;
    }

    @Override
    public String toString() {
        return "CommandReceipt{commandNo=" + commandNo
                + ", resourceId=" + resourceId
                + ", targetKw=" + targetKw
                + ", status=" + status
                + ", reason=" + reason
                + ", ackedAtMs=" + ackedAtMs
                + '}';
    }
}
