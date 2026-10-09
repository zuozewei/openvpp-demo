package com.openvpp.dispatch.command;

import com.openvpp.iot.simulate.CommandReceipt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 单桩派发指令 —— 分级计划到设备通道之间的指令关联载体（第 54 篇底座，
 * 规划类 DispatchCommand，对应验收格"下发证据"）。
 *
 * 证据三列分明，永不混写：
 * <ul>
 *   <li>adjustKw（调节量）：计划分到的调节责任，核算侧参照；</li>
 *   <li>baselineKw（基线）：响应量核算参照侧；</li>
 *   <li>targetPowerKw（绝对目标功率）：唯一下发值 —— 基线 ± 调节量的方向换算
 *       在派单链路侧完成后才进入本指令，下行通道只接收绝对值。</li>
 * </ul>
 *
 * 下发证据链（计划确认、发送、回执、达标分列，无证据不标完成）：
 * sentAt（平台发送时标）、receiptAckedAtMs（设备侧回执时标，毫秒）分列记录；
 * 回执 ≠ 达标 —— 功率到位由后续监测工序按遥测判定，本指令不承载达标结论。
 *
 * 指令编号 commandNo 是设备侧去重键：同一编号重复投递被设备拒绝
 * （DUPLICATE_COMMAND），确需重发必须换新编号并关联原编号（originalCommandNo）。
 */
public class DispatchCommand {

    private final String commandNo;
    /** 关联分级计划标识（planId + planVersion 锚定版本，指令证据不随计划修订漂移） */
    private final String planId;
    private final int planVersion;
    private final String resourceId;
    private final String stationId;
    private final BigDecimal adjustKw;
    private final BigDecimal baselineKw;
    private final BigDecimal targetPowerKw;
    /** 补发链：本指令为补发时指向原指令编号；首发为 null */
    private final String originalCommandNo;
    private final LocalDateTime createdAt;

    private CommandState state = CommandState.CREATED;
    /** 发送时标（平台侧，毫秒与时标分列证据之一） */
    private LocalDateTime sentAt;
    /** 回执时标（设备侧受理时刻，毫秒）；无回执为 null */
    private Long receiptAckedAtMs;
    /** 回执结论：ACCEPTED / REJECTED；无回执为 null */
    private String receiptStatus;
    /** 拒绝原因码（机器可读，如 TARGET_OUT_OF_RANGE）；受理/无回执为 null */
    private String rejectReason;

    /**
     * 首发指令。
     *
     * @param targetPowerKw 绝对目标功率（kW，≥ 0；零目标合法 —— 停机/回原状）
     */
    public DispatchCommand(String commandNo, String planId, int planVersion,
                           String resourceId, String stationId,
                           BigDecimal adjustKw, BigDecimal baselineKw, BigDecimal targetPowerKw,
                           LocalDateTime createdAt) {
        this(commandNo, planId, planVersion, resourceId, stationId,
                adjustKw, baselineKw, targetPowerKw, null, createdAt);
    }

    private DispatchCommand(String commandNo, String planId, int planVersion,
                            String resourceId, String stationId,
                            BigDecimal adjustKw, BigDecimal baselineKw, BigDecimal targetPowerKw,
                            String originalCommandNo, LocalDateTime createdAt) {
        if (commandNo == null || commandNo.isBlank()) {
            throw new IllegalArgumentException("业务指令编号不能为空");
        }
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("关联计划标识不能为空");
        }
        if (planVersion < 1) {
            throw new IllegalArgumentException("关联计划版本必须 ≥ 1: " + planVersion);
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("桩资源标识不能为空");
        }
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("归属场站不能为空");
        }
        if (targetPowerKw == null || targetPowerKw.signum() < 0) {
            throw new IllegalArgumentException("绝对目标功率不得为负（零目标合法）: " + targetPowerKw);
        }
        this.commandNo = commandNo;
        this.planId = planId;
        this.planVersion = planVersion;
        this.resourceId = resourceId;
        this.stationId = stationId;
        this.adjustKw = requireNonNegative(adjustKw, "调节量");
        this.baselineKw = requireNonNegative(baselineKw, "基线");
        this.targetPowerKw = targetPowerKw;
        this.originalCommandNo = originalCommandNo;
        this.createdAt = Objects.requireNonNull(createdAt, "创建时刻不能为空");
    }

    /** 构造补发指令：新编号 + 关联原编号，证据三列原样继承，状态回到 CREATED */
    DispatchCommand successor(String newCommandNo, LocalDateTime at) {
        return new DispatchCommand(newCommandNo, planId, planVersion, resourceId, stationId,
                adjustKw, baselineKw, targetPowerKw, commandNo, at);
    }

    /** 登记发送：CREATED → SENT，记录发送时标（先于通道调用，失联也有发送证据） */
    void markSent(LocalDateTime at) {
        if (state != CommandState.CREATED) {
            throw new IllegalStateException("仅已创建状态可登记发送: " + commandNo + " 当前 " + state);
        }
        this.sentAt = Objects.requireNonNull(at, "发送时刻不能为空");
        this.state = CommandState.SENT;
    }

    /**
     * 记录设备回执（SENT 态）：受理转 ACKED、拒绝转 REJECTED 并留原因码；
     * 回执编号/资源/目标回显与本指令逐一对账，不符即拒记（下发证据链不允许错挂）。
     */
    void recordReceipt(CommandReceipt receipt) {
        if (state != CommandState.SENT) {
            throw new IllegalStateException("仅已发送状态可记录回执: " + commandNo + " 当前 " + state);
        }
        Objects.requireNonNull(receipt, "回执不能为空");
        if (!receipt.getCommandNo().equals(commandNo)) {
            throw new IllegalArgumentException("回执编号与指令不符: 指令 " + commandNo + "，回执 " + receipt.getCommandNo());
        }
        if (!receipt.getResourceId().equals(resourceId)) {
            throw new IllegalArgumentException("回执资源与指令不符: 指令 " + commandNo + " → " + resourceId
                    + "，回执 " + receipt.getResourceId());
        }
        if (receipt.getTargetKw().compareTo(targetPowerKw) != 0) {
            throw new IllegalArgumentException("回执目标回显与指令不符: 指令 " + commandNo + " 目标 "
                    + targetPowerKw + " kW，回显 " + receipt.getTargetKw() + " kW");
        }
        this.receiptAckedAtMs = receipt.getAckedAtMs();
        switch (receipt.getStatus()) {
            case ACCEPTED:
                this.receiptStatus = "ACCEPTED";
                this.state = CommandState.ACKED;
                break;
            case REJECTED:
                this.receiptStatus = "REJECTED";
                this.rejectReason = receipt.getReason();
                this.state = CommandState.REJECTED;
                break;
            default:
                throw new IllegalArgumentException("未知回执状态: " + receipt.getStatus());
        }
    }

    /** 失联无回执：SENT → NO_RECEIPT（进入核查，不是失败断言，禁止直接重发） */
    void markNoReceipt() {
        if (state != CommandState.SENT) {
            throw new IllegalStateException("仅已发送状态可标记无回执: " + commandNo + " 当前 " + state);
        }
        this.state = CommandState.NO_RECEIPT;
    }

    public String getCommandNo() {
        return commandNo;
    }

    public String getPlanId() {
        return planId;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getStationId() {
        return stationId;
    }

    /** 调节量（kW，证据列，核算侧参照，非下发值） */
    public BigDecimal getAdjustKw() {
        return adjustKw;
    }

    /** 基线（kW，证据列，响应量核算参照侧） */
    public BigDecimal getBaselineKw() {
        return baselineKw;
    }

    /** 绝对目标功率（kW，唯一下发值；与调节量分列） */
    public BigDecimal getTargetPowerKw() {
        return targetPowerKw;
    }

    /** 补发链：本指令为补发时指向原指令编号；首发为 null */
    public String getOriginalCommandNo() {
        return originalCommandNo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public CommandState getState() {
        return state;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    /** 回执时标（设备侧，毫秒）；无回执为 null */
    public Long getReceiptAckedAtMs() {
        return receiptAckedAtMs;
    }

    /** 回执结论：ACCEPTED / REJECTED；无回执为 null */
    public String getReceiptStatus() {
        return receiptStatus;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + "不得为负: " + value);
        }
        return value;
    }
}
