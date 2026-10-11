package com.openvpp.app.controller.operations;

import com.openvpp.settlement.charge.AllocationLine;
import com.openvpp.settlement.charge.CorrectionRecord;
import com.openvpp.settlement.charge.PaymentRecord;
import com.openvpp.settlement.charge.SettlementOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算接口的响应视图装配 —— 领域对象 → 前端契约 Map（camelCase）。
 *
 * 与 AssessmentResponseViews 同一口径：响应字段由本类显式列出，领域对象不直接
 * 序列化；枚举给 code + 中文标签；四量（毛额/考核扣款/净实收/分账）与评估版本
 * 三要素、规则快照、账单版本齐备呈现；付款登记显式标注"模拟"语义，不把模拟
 * 付款包装成真实到账；历史版本与更正链完整呈现，更正差额可独立复算。
 */
final class SettlementResponseViews {

    private SettlementResponseViews() {
    }

    /** 结算单视图：四量 + 评估版本三要素 + 规则快照 + 分账明细 + 单据状态 */
    static Map<String, Object> settlement(SettlementOrder order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settlementId", order.getSettlementId());
        body.put("billVersion", order.getBillVersion());
        body.put("eventId", order.getEventId());
        body.put("organizerTenantId", order.getOrganizerTenantId());
        body.put("stationId", order.getStationId());
        body.put("resourceId", order.getResourceId());
        // 评估版本三要素：评估标识 + 评估规则版本 + 基线版本（消费核定电量的出处）
        body.put("assessmentId", order.getAssessmentId());
        body.put("assessmentRuleVersion", order.getAssessmentRuleVersion());
        body.put("baselineVersion", order.getBaselineVersion());
        // 四量：核定电量 × 单价 = 毛额 − 考核扣款 = 净实收 → 分账
        body.put("confirmedResponseKwh", order.getConfirmedResponseKwh());
        body.put("priceYuanPerKwh", order.getPriceYuanPerKwh());
        body.put("grossYuan", order.getGrossYuan());
        body.put("deductionYuan", order.getDeductionYuan());
        body.put("netYuan", order.getNetYuan());
        // 分账规则快照随单呈现（比例总和 = 1）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ruleVersion", order.getRuleSnapshot().getRuleVersion());
        snapshot.put("platformRate", order.getRuleSnapshot().getPlatformRate());
        snapshot.put("operatorRate", order.getRuleSnapshot().getOperatorRate());
        snapshot.put("stationRate", order.getRuleSnapshot().getStationRate());
        body.put("ruleSnapshot", snapshot);
        // 分账明细：三方主体、归属标识、比例、金额（合计恒等于净实收）
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (AllocationLine line : order.getAllocationLines()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("party", line.getParty().name());
            view.put("partyLabel", line.getParty().getLabel());
            view.put("partyId", line.getPartyId());
            view.put("rate", line.getRate());
            view.put("amountYuan", line.getAmountYuan());
            allocations.add(view);
        }
        body.put("allocations", allocations);
        // 单据状态与确认留痕
        body.put("status", order.getStatus().name());
        body.put("statusLabel", order.getStatus().getLabel());
        body.put("confirmedBy", order.getConfirmedBy());
        body.put("confirmedAt", order.getConfirmedAt());
        body.put("createdBy", order.getCreatedBy());
        body.put("createdAt", order.getCreatedAt());
        return body;
    }

    /** 结算单详情视图：单据 + 历史版本 + 更正链 + 付款登记记录 */
    static Map<String, Object> settlementDetail(SettlementOrder order,
                                                List<SettlementOrder> history,
                                                List<CorrectionRecord> corrections,
                                                List<PaymentRecord> payments) {
        Map<String, Object> body = settlement(order);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (SettlementOrder version : history) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("billVersion", version.getBillVersion());
            view.put("status", version.getStatus().name());
            view.put("statusLabel", version.getStatus().getLabel());
            view.put("grossYuan", version.getGrossYuan());
            view.put("deductionYuan", version.getDeductionYuan());
            view.put("netYuan", version.getNetYuan());
            view.put("confirmedBy", version.getConfirmedBy());
            view.put("confirmedAt", version.getConfirmedAt());
            versions.add(view);
        }
        body.put("history", versions);
        List<Map<String, Object>> correctionViews = new ArrayList<>();
        for (CorrectionRecord record : corrections) {
            correctionViews.add(correction(record));
        }
        body.put("corrections", correctionViews);
        List<Map<String, Object>> paymentViews = new ArrayList<>();
        for (PaymentRecord record : payments) {
            paymentViews.add(payment(record));
        }
        body.put("payments", paymentViews);
        return body;
    }

    /** 更正记录视图：请求标识、前后版本、差额、操作主体、时间、原因齐备 */
    static Map<String, Object> correction(CorrectionRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("correctionRequestId", record.getCorrectionRequestId());
        body.put("settlementId", record.getSettlementId());
        body.put("fromVersion", record.getFromVersion());
        body.put("toVersion", record.getToVersion());
        body.put("diffYuan", record.getDiffYuan());
        body.put("operator", record.getOperator());
        body.put("correctedAt", record.getCorrectedAt());
        body.put("reason", record.getReason());
        return body;
    }

    /** 付款登记视图：显式标注模拟渠道，金额与账单版本留痕 */
    static Map<String, Object> payment(PaymentRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentRequestId", record.getPaymentRequestId());
        body.put("settlementId", record.getSettlementId());
        body.put("billVersion", record.getBillVersion());
        body.put("amountYuan", record.getAmountYuan());
        body.put("channel", record.getChannel());
        body.put("channelLabel", "模拟付款（不接真实资金通道）");
        body.put("registeredBy", record.getRegisteredBy());
        body.put("registeredAt", record.getRegisteredAt());
        return body;
    }
}
