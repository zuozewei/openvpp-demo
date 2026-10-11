package com.openvpp.settlement.charge;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 结算单 —— 一次需求响应结算的版本化单据（第 57 篇底座，规划类 SettlementOrder，
 * 3.5.2"结算单与分账明细"行：评估版本、补偿毛额、考核扣款、净实收、分账规则快照、
 * 各主体金额、账单版本、确认/付款登记）。
 *
 * 四量口径（对照姊妹专栏《虚拟电厂系统开发实战》第 21 篇，不另造相反公式）：
 * 补偿毛额 = 核定响应电量 × 补偿单价；净实收 = 毛额 − 考核扣款；
 * 分账合计恒等于净实收（残差归并见 {@link SettlementCalculator}）。
 *
 * 版本口径：
 * 1. 结算单消费 {@code AssessmentRecord.confirmedResponseKwh}（仅 CONFIRMED 结论
 *    入结算），评估标识 + 评估规则版本 + 基线版本随单留痕（"评估版本"三要素）；
 * 2. 账单版本自 1 起，每次更正 +1 —— 更正生成新版本单据，历史版本对象原样留存，
 *    任何字段不回填、不覆盖；
 * 3. 分账规则快照随单保存：规则变更（新比例）只影响新单与更正单，
 *    已确认历史的快照就是当时口径的证据；
 * 4. 金额字段全部不可变：更正不是改数，是按更正输入重算一个新版本。
 */
public final class SettlementOrder {

    private final String settlementId;
    /** 账单版本：自 1 起，每次更正 +1 */
    private final int billVersion;
    private final String eventId;
    private final String organizerTenantId;
    private final String stationId;
    private final String resourceId;
    /** 评估版本三要素：评估标识 + 评估规则版本 + 基线版本 */
    private final String assessmentId;
    private final String assessmentRuleVersion;
    private final String baselineVersion;
    /** 核定响应电量（kWh，结算依据 —— 只看板估算之外的核定量） */
    private final double confirmedResponseKwh;
    /** 补偿单价（元/kWh，教学假设输入，随单留痕） */
    private final BigDecimal priceYuanPerKwh;
    /** 补偿毛额（元）= 核定电量 × 单价 */
    private final BigDecimal grossYuan;
    /** 考核扣款（元，≥ 0 且 ≤ 毛额，超额由线下追偿处理） */
    private final BigDecimal deductionYuan;
    /** 净实收（元）= 毛额 − 考核扣款 */
    private final BigDecimal netYuan;
    private final SharingRuleSnapshot ruleSnapshot;
    private final List<AllocationLine> allocationLines;

    private SettlementStatus status;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private final String createdBy;
    private final LocalDateTime createdAt;

    public SettlementOrder(String settlementId, int billVersion,
                           String eventId, String organizerTenantId, String stationId, String resourceId,
                           String assessmentId, String assessmentRuleVersion, String baselineVersion,
                           double confirmedResponseKwh, BigDecimal priceYuanPerKwh,
                           BigDecimal grossYuan, BigDecimal deductionYuan, BigDecimal netYuan,
                           SharingRuleSnapshot ruleSnapshot, List<AllocationLine> allocationLines,
                           String createdBy, LocalDateTime createdAt) {
        this.settlementId = requireText(settlementId, "结算单标识");
        if (billVersion < 1) {
            throw new IllegalArgumentException("账单版本必须 ≥ 1: " + billVersion);
        }
        this.billVersion = billVersion;
        this.eventId = requireText(eventId, "事件标识");
        this.organizerTenantId = requireText(organizerTenantId, "组织方租户");
        this.stationId = requireText(stationId, "场站标识");
        this.resourceId = requireText(resourceId, "资源标识");
        this.assessmentId = requireText(assessmentId, "评估标识");
        this.assessmentRuleVersion = requireText(assessmentRuleVersion, "评估规则版本");
        this.baselineVersion = requireText(baselineVersion, "基线版本");
        if (confirmedResponseKwh < 0) {
            throw new IllegalArgumentException("核定响应电量不得为负: " + confirmedResponseKwh);
        }
        this.confirmedResponseKwh = confirmedResponseKwh;
        this.priceYuanPerKwh = requirePositive(priceYuanPerKwh, "补偿单价");
        this.grossYuan = money(grossYuan, "补偿毛额");
        this.deductionYuan = money(deductionYuan, "考核扣款");
        if (this.deductionYuan.signum() < 0) {
            throw new IllegalArgumentException("考核扣款不得为负: " + deductionYuan);
        }
        if (this.deductionYuan.compareTo(this.grossYuan) > 0) {
            throw new IllegalArgumentException("考核扣款不得超过补偿毛额: 扣款 " + deductionYuan
                    + " > 毛额 " + grossYuan + "（超额部分走线下追偿）");
        }
        this.netYuan = money(netYuan, "净实收");
        if (this.netYuan.compareTo(this.grossYuan.subtract(this.deductionYuan)) != 0) {
            throw new IllegalArgumentException("净实收必须等于毛额减考核扣款: " + netYuan);
        }
        this.ruleSnapshot = Objects.requireNonNull(ruleSnapshot, "分账规则快照不能为空");
        if (allocationLines == null || allocationLines.size() != AllocationParty.values().length) {
            throw new IllegalArgumentException("分账明细必须覆盖平台/运营商/场站三方");
        }
        this.allocationLines = List.copyOf(allocationLines);
        BigDecimal lineSum = this.allocationLines.stream()
                .map(AllocationLine::getAmountYuan)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (lineSum.compareTo(this.netYuan) != 0) {
            throw new IllegalArgumentException("分账合计必须恒等于净实收: 明细合计 " + lineSum
                    + " ≠ 净实收 " + this.netYuan);
        }
        this.createdBy = requireText(createdBy, "制单人");
        this.createdAt = Objects.requireNonNull(createdAt, "制单时刻不能为空");
        this.status = SettlementStatus.CREATED;
    }

    /** 确认留痕（仅服务层在守卫通过后调用）：CREATED → CONFIRMED */
    void markConfirmed(String operator, LocalDateTime at) {
        status.assertTransitTo(SettlementStatus.CONFIRMED);
        this.confirmedBy = requireText(operator, "确认人");
        this.confirmedAt = Objects.requireNonNull(at, "确认时刻不能为空");
        this.status = SettlementStatus.CONFIRMED;
    }

    /** 付款登记状态迁移（仅服务层在守卫通过后调用）：CONFIRMED → PAID */
    void markPaid() {
        status.assertTransitTo(SettlementStatus.PAID);
        this.status = SettlementStatus.PAID;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public int getBillVersion() {
        return billVersion;
    }

    public String getEventId() {
        return eventId;
    }

    public String getOrganizerTenantId() {
        return organizerTenantId;
    }

    public String getStationId() {
        return stationId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getAssessmentId() {
        return assessmentId;
    }

    public String getAssessmentRuleVersion() {
        return assessmentRuleVersion;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public double getConfirmedResponseKwh() {
        return confirmedResponseKwh;
    }

    public BigDecimal getPriceYuanPerKwh() {
        return priceYuanPerKwh;
    }

    public BigDecimal getGrossYuan() {
        return grossYuan;
    }

    public BigDecimal getDeductionYuan() {
        return deductionYuan;
    }

    public BigDecimal getNetYuan() {
        return netYuan;
    }

    public SharingRuleSnapshot getRuleSnapshot() {
        return ruleSnapshot;
    }

    public List<AllocationLine> getAllocationLines() {
        return allocationLines;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须为正: " + value);
        }
        return value;
    }

    private static BigDecimal money(BigDecimal value, String name) {
        return Objects.requireNonNull(value, name + "不能为空").setScale(2);
    }

    @Override
    public String toString() {
        return "SettlementOrder{settlementId=" + settlementId + ", billVersion=" + billVersion
                + ", eventId=" + eventId + ", stationId=" + stationId + ", resourceId=" + resourceId
                + ", assessmentId=" + assessmentId
                + ", grossYuan=" + grossYuan + ", deductionYuan=" + deductionYuan
                + ", netYuan=" + netYuan + ", status=" + status + '}';
    }
}
