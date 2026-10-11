package com.openvpp.settlement.charge;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 分账明细行 —— 一方主体在一次结算中的应分金额（第 57 篇底座，
 * 3.5.2"结算单与分账明细"行"各主体金额"列）。
 *
 * 行内同时留痕：主体角色、主体标识（归属链解析结果，非请求方自填）、
 * 适用比例、应分金额。金额按元保留 2 位；全部分账行合计恒等于净实收
 * （尾差归并规则见 {@link SettlementService}，确定性同输入同输出）。
 */
public final class AllocationLine {

    private final AllocationParty party;
    /** 主体标识：平台=组织方租户，运营商=归属主体，场站=场站标识 */
    private final String partyId;
    private final BigDecimal rate;
    private final BigDecimal amountYuan;

    public AllocationLine(AllocationParty party, String partyId,
                          BigDecimal rate, BigDecimal amountYuan) {
        this.party = Objects.requireNonNull(party, "分账主体角色不能为空");
        if (partyId == null || partyId.isBlank()) {
            throw new IllegalArgumentException("分账主体标识不能为空");
        }
        this.partyId = partyId;
        this.rate = Objects.requireNonNull(rate, "适用比例不能为空");
        this.amountYuan = Objects.requireNonNull(amountYuan, "应分金额不能为空");
    }

    public AllocationParty getParty() {
        return party;
    }

    public String getPartyId() {
        return partyId;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getAmountYuan() {
        return amountYuan;
    }

    @Override
    public String toString() {
        return "AllocationLine{party=" + party + ", partyId=" + partyId
                + ", rate=" + rate + ", amountYuan=" + amountYuan + '}';
    }
}
