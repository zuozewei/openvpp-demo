package com.openvpp.settlement.charge;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 付款登记记录 —— 一次（模拟）付款结果的登记留痕（第 57 篇底座，3.5.2
 * "结算单与分账明细"行：确认/付款登记；3.5.5"结算与更正"格：只产生模拟付款记录）。
 *
 * 口径：
 * 1. 仅模拟 —— channel 固定 SIMULATED，本工程不接任何真实资金通道，
 *    记录不代表真实到账，对外表述不得写作"已付款/已到账"；
 * 2. 请求幂等 —— paymentRequestId 去重：同请求标识重试返回原记录，
 *    不同请求标识对同一单据重复登记一律拒绝（见 {@link SettlementService}）；
 * 3. 金额留痕 —— 登记金额 = 登记时单据净实收，与账单版本绑定可复算。
 */
public final class PaymentRecord {

    /** 模拟付款渠道标识：全工程唯一合法值，真实资金通道严禁接入 */
    public static final String CHANNEL_SIMULATED = "SIMULATED";

    private final String paymentRequestId;
    private final String settlementId;
    /** 登记时的账单版本（版本留痕，旧版本不覆盖） */
    private final int billVersion;
    private final BigDecimal amountYuan;
    private final String channel;
    private final String registeredBy;
    private final LocalDateTime registeredAt;

    public PaymentRecord(String paymentRequestId, String settlementId, int billVersion,
                         BigDecimal amountYuan, String registeredBy, LocalDateTime registeredAt) {
        if (paymentRequestId == null || paymentRequestId.isBlank()) {
            throw new IllegalArgumentException("付款登记请求标识不能为空");
        }
        if (settlementId == null || settlementId.isBlank()) {
            throw new IllegalArgumentException("结算单标识不能为空");
        }
        if (billVersion < 1) {
            throw new IllegalArgumentException("账单版本必须 ≥ 1: " + billVersion);
        }
        this.paymentRequestId = paymentRequestId;
        this.settlementId = settlementId;
        this.billVersion = billVersion;
        this.amountYuan = Objects.requireNonNull(amountYuan, "登记金额不能为空").setScale(2);
        this.channel = CHANNEL_SIMULATED;
        if (registeredBy == null || registeredBy.isBlank()) {
            throw new IllegalArgumentException("登记操作主体不能为空");
        }
        this.registeredBy = registeredBy;
        this.registeredAt = Objects.requireNonNull(registeredAt, "登记时刻不能为空");
    }

    public String getPaymentRequestId() {
        return paymentRequestId;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public int getBillVersion() {
        return billVersion;
    }

    public BigDecimal getAmountYuan() {
        return amountYuan;
    }

    public String getChannel() {
        return channel;
    }

    public String getRegisteredBy() {
        return registeredBy;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    @Override
    public String toString() {
        return "PaymentRecord{paymentRequestId=" + paymentRequestId
                + ", settlementId=" + settlementId + ", billVersion=" + billVersion
                + ", amountYuan=" + amountYuan + ", channel=" + channel
                + ", registeredBy=" + registeredBy + ", registeredAt=" + registeredAt + '}';
    }
}
