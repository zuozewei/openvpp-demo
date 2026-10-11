package com.openvpp.settlement.charge;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 更正记录 —— 一次更正的完整审计留痕（第 57 篇底座，规划类更正审计，
 * 3.5.2"更正与审计"行：更正请求标识、前后版本、差额、操作主体、时间与原因齐备）。
 *
 * 口径：
 * 1. 更正请求标识幂等 —— 同一 correctionRequestId 重复提交返回原版本，
 *    不重复生成账单版本（登记簿强制，见 {@link SettlementService#correct}）；
 * 2. 差额可复算 —— diffYuan = 新版本净实收 − 前版本净实收，
 *    审计落账的同时保留前后版本引用，任一方都可独立复算核对；
 * 3. 更正不改写历史 —— 记录只追加，历史版本与其更正链永久可查。
 */
public final class CorrectionRecord {

    private final String correctionRequestId;
    private final String settlementId;
    /** 前版本（被更正的账单版本） */
    private final int fromVersion;
    /** 后版本（更正产生的新账单版本） */
    private final int toVersion;
    /** 更正差额（元）= to.net − from.net，可为负（下调） */
    private final BigDecimal diffYuan;
    private final String operator;
    private final LocalDateTime correctedAt;
    private final String reason;

    public CorrectionRecord(String correctionRequestId, String settlementId,
                            int fromVersion, int toVersion, BigDecimal diffYuan,
                            String operator, LocalDateTime correctedAt, String reason) {
        if (correctionRequestId == null || correctionRequestId.isBlank()) {
            throw new IllegalArgumentException("更正请求标识不能为空");
        }
        if (settlementId == null || settlementId.isBlank()) {
            throw new IllegalArgumentException("结算单标识不能为空");
        }
        if (toVersion <= fromVersion) {
            throw new IllegalArgumentException("后版本必须大于前版本: " + fromVersion + " → " + toVersion);
        }
        this.correctionRequestId = correctionRequestId;
        this.settlementId = settlementId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.diffYuan = Objects.requireNonNull(diffYuan, "更正差额不能为空").setScale(2);
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("更正操作主体不能为空");
        }
        this.operator = operator;
        this.correctedAt = Objects.requireNonNull(correctedAt, "更正时刻不能为空");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("更正原因不能为空");
        }
        this.reason = reason;
    }

    public String getCorrectionRequestId() {
        return correctionRequestId;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public int getFromVersion() {
        return fromVersion;
    }

    public int getToVersion() {
        return toVersion;
    }

    public BigDecimal getDiffYuan() {
        return diffYuan;
    }

    public String getOperator() {
        return operator;
    }

    public LocalDateTime getCorrectedAt() {
        return correctedAt;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "CorrectionRecord{correctionRequestId=" + correctionRequestId
                + ", settlementId=" + settlementId
                + ", version=" + fromVersion + "→" + toVersion
                + ", diffYuan=" + diffYuan + ", operator=" + operator
                + ", correctedAt=" + correctedAt + ", reason=" + reason + '}';
    }
}
