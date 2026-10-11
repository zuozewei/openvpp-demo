package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.settlement.charge.CorrectionRecord;
import com.openvpp.settlement.charge.PaymentRecord;
import com.openvpp.settlement.charge.SettlementOrder;
import com.openvpp.settlement.charge.SettlementService;
import com.openvpp.settlement.charge.SharingRuleSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多级分账结算接口（3.5.3 接口契约第 8 行"结算办理：平台生成、确认、付款登记"）。
 *
 * 安全边界：
 * 1. 角色 —— 生成/确认/付款登记/更正仅平台（@RequireRole 服务端拦截，越角色 401），
 *    查询三方均可；数据范围一律由服务端会话解析，平台看本租户，运营商/场站仅看
 *    绑定场站单据，空范围短路返回空，禁止退化为全量查询；
 * 2. 结算守门 —— 仅 CONFIRMED 结论的评估入结算；分账比例总和必须为 1（构造即校验）；
 *    考核扣款不得超过毛额；请求标识幂等（生成/更正/付款登记重试返回原单/原版本/原记录）；
 * 3. 越权口径 —— 不在当前数据范围内的结算单一律 404（未登记与无权限口径一致）；
 *    跨租户更正与越权确认由服务层 DataScope 守卫 + 本层角色校验双重拦截；
 * 4. 审计 —— 生成/确认/付款登记/更正均留痕（SUCCESS 与 REJECTED 分列），
 *    更正记录自留前后版本、差额、操作主体、时间与原因（审计视角全量可查）；
 * 5. 付款登记仅模拟 —— 渠道固定 SIMULATED，不接真实资金通道，响应与状态文案
 *    均带"模拟"语义。
 *
 * 呈现口径：四量（毛额/考核扣款/净实收/分账）+ 评估版本三要素 + 规则快照 +
 * 账单版本齐备；历史版本与更正链完整呈现，更正差额可由前后版本净实收独立复算。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsSettlementController {

    public static final String ACTION_SETTLEMENT_GENERATE = "SETTLEMENT_GENERATE";
    public static final String ACTION_SETTLEMENT_CONFIRM = "SETTLEMENT_CONFIRM";
    public static final String ACTION_SETTLEMENT_PAYMENT = "SETTLEMENT_PAYMENT";
    public static final String ACTION_SETTLEMENT_CORRECT = "SETTLEMENT_CORRECT";
    public static final String TARGET_SETTLEMENT = "CHARGE_SETTLEMENT";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private final SettlementService settlementService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OperationsSettlementController(SettlementService settlementService,
                                          AuditLogService auditLogService,
                                          Clock clock) {
        this.settlementService = settlementService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 生成结算单（平台）：输入评估标识 + 补偿单价 + 考核扣款 + 分账规则 + 请求标识，
     * 输出四量齐备的结算单（账单版本 v1）。重复请求返回原单。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/settlements")
    public Map<String, Object> create(@RequestBody(required = false) GenerateSettlementRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        if (request == null || request.getAssessmentId() == null || request.getAssessmentId().isBlank()) {
            throw new IllegalArgumentException("评估标识 assessmentId 不能为空");
        }
        if (request.getPriceYuanPerKwh() == null) {
            throw new IllegalArgumentException("补偿单价 priceYuanPerKwh 不能为空（教学假设输入，须为正）");
        }
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new IllegalArgumentException("生成请求标识 requestId 不能为空（幂等键）");
        }
        if (request.getRule() == null
                || request.getRule().getPlatformRate() == null
                || request.getRule().getOperatorRate() == null
                || request.getRule().getStationRate() == null) {
            throw new IllegalArgumentException("分账规则 rule.platformRate/operatorRate/stationRate 不能为空");
        }
        String assessmentId = request.getAssessmentId().trim();
        try {
            SharingRuleSnapshot rule = buildRule(request.getRule());
            SettlementOrder order = settlementService.create(scope, assessmentId,
                    request.getPriceYuanPerKwh(), request.getDeductionYuan(), rule,
                    request.getRequestId().trim(), context.getAccountId(), LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_GENERATE, TARGET_SETTLEMENT,
                    order.getSettlementId(), "v" + order.getBillVersion(), RESULT_SUCCESS,
                    "毛额 " + order.getGrossYuan() + " − 扣款 " + order.getDeductionYuan()
                            + " = 净实收 " + order.getNetYuan() + "，分账 3 方（规则快照 "
                            + rule.getRuleVersion() + "）");
            return SettlementResponseViews.settlement(order);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_GENERATE, TARGET_SETTLEMENT,
                    assessmentId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /**
     * 确认结算单（平台）：并发确认幂等（首条留痕为准），旧版本不覆盖。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/settlements/{settlementId}/confirm")
    public Map<String, Object> confirm(@PathVariable String settlementId) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        // 可见性：不在当前数据范围内的结算单一律 404（未登记与无权限口径一致）
        visibleOrder(scope, settlementId);
        try {
            boolean transitioned = settlementService.confirm(scope, settlementId,
                    context.getAccountId(), LocalDateTime.now(clock));
            SettlementOrder latest = settlementService.requireLatest(scope, settlementId);
            if (transitioned) {
                auditLogService.recordForCurrent(ACTION_SETTLEMENT_CONFIRM, TARGET_SETTLEMENT,
                        settlementId, "v" + latest.getBillVersion(), RESULT_SUCCESS,
                        "净实收 " + latest.getNetYuan() + " 确认留痕");
            }
            return SettlementResponseViews.settlement(latest);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_CONFIRM, TARGET_SETTLEMENT,
                    settlementId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /**
     * 付款登记（平台）：仅登记模拟结果（渠道 SIMULATED，不接真实资金通道），
     * 请求标识重试幂等。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/settlements/{settlementId}/payments")
    public Map<String, Object> registerPayment(@PathVariable String settlementId,
                                               @RequestBody(required = false) RegisterPaymentRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        visibleOrder(scope, settlementId);
        if (request == null || request.getPaymentRequestId() == null
                || request.getPaymentRequestId().isBlank()) {
            throw new IllegalArgumentException("付款登记请求标识 paymentRequestId 不能为空（幂等键）");
        }
        try {
            PaymentRecord record = settlementService.registerPayment(scope, settlementId,
                    request.getPaymentRequestId().trim(), context.getAccountId(), LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_PAYMENT, TARGET_SETTLEMENT,
                    settlementId, "v" + record.getBillVersion(), RESULT_SUCCESS,
                    "模拟付款登记 " + record.getAmountYuan() + " 元（渠道 SIMULATED）");
            return SettlementResponseViews.payment(record);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_PAYMENT, TARGET_SETTLEMENT,
                    settlementId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /**
     * 更正结算单（平台）：更正请求标识幂等，生成新版本单据（历史版本留存），
     * 更正记录齐备（前后版本、差额、操作主体、时间、原因），差额可复算。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/settlements/{settlementId}/corrections")
    public Map<String, Object> correct(@PathVariable String settlementId,
                                       @RequestBody(required = false) CorrectSettlementRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        visibleOrder(scope, settlementId);
        if (request == null || request.getCorrectionRequestId() == null
                || request.getCorrectionRequestId().isBlank()) {
            throw new IllegalArgumentException("更正请求标识 correctionRequestId 不能为空（幂等键）");
        }
        if (request.getConfirmedResponseKwh() == null) {
            throw new IllegalArgumentException("更正后的核定响应电量 confirmedResponseKwh 不能为空");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("更正原因 reason 不能为空（留痕必备）");
        }
        try {
            SettlementOrder corrected = settlementService.correct(scope, settlementId,
                    request.getCorrectionRequestId().trim(), request.getConfirmedResponseKwh(),
                    request.getDeductionYuan(), request.getReason().trim(),
                    context.getAccountId(), LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_CORRECT, TARGET_SETTLEMENT,
                    settlementId, "v" + corrected.getBillVersion(), RESULT_SUCCESS,
                    "更正生成 v" + corrected.getBillVersion() + "（净实收 " + corrected.getNetYuan()
                            + "）原因: " + request.getReason().trim());
            return SettlementResponseViews.settlement(corrected);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_SETTLEMENT_CORRECT, TARGET_SETTLEMENT,
                    settlementId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 结算单查询：按当前身份数据范围（事件标识可选过滤，运营商/场站仅见所属场站单据） */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/settlements")
    public List<Map<String, Object>> list(@RequestParam(required = false) String eventId) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return settlementService.query(scope).stream()
                .filter(order -> eventId == null || eventId.isBlank()
                        || order.getEventId().equals(eventId))
                .map(SettlementResponseViews::settlement)
                .collect(Collectors.toList());
    }

    /**
     * 结算单详情：单据四量 + 分账明细 + 全版本历史 + 更正链 + 付款登记记录；
     * 不在当前数据范围内一律 404。
     */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/settlements/{settlementId}")
    public Map<String, Object> detail(@PathVariable String settlementId) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        SettlementOrder latest = visibleOrder(scope, settlementId);
        List<SettlementOrder> history = settlementService.history(scope, settlementId);
        List<CorrectionRecord> corrections = settlementService.corrections(scope).stream()
                .filter(record -> record.getSettlementId().equals(settlementId))
                .collect(Collectors.toList());
        List<PaymentRecord> payments = settlementService.paymentsOf(scope, settlementId);
        return SettlementResponseViews.settlementDetail(latest, history, corrections, payments);
    }

    // ---------------- 内部 ----------------

    /** 数据范围内结算单取用：不可见即 404（未登记与无权限口径一致） */
    private SettlementOrder visibleOrder(DataScope scope, String settlementId) {
        try {
            return settlementService.requireLatest(scope, settlementId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new OperationsResourceNotFoundException(
                    "结算单不存在或不在当前数据范围内: " + settlementId);
        }
    }

    /** 分账规则装配：可带自定义版本，缺省用教学默认版本与来源说明 */
    private static SharingRuleSnapshot buildRule(SharingRuleRequest ruleRequest) {
        if (ruleRequest.getRuleVersion() == null || ruleRequest.getRuleVersion().isBlank()) {
            return SharingRuleSnapshot.teaching(ruleRequest.getPlatformRate(),
                    ruleRequest.getOperatorRate(), ruleRequest.getStationRate());
        }
        return new SharingRuleSnapshot(ruleRequest.getRuleVersion().trim(),
                SharingRuleSnapshot.TEACHING_RULE_SOURCE,
                ruleRequest.getPlatformRate(), ruleRequest.getOperatorRate(),
                ruleRequest.getStationRate());
    }

    /** 生成结算单请求体：评估标识 + 补偿单价 + 考核扣款（可选）+ 分账规则 + 请求标识 */
    public static final class GenerateSettlementRequest {
        private String assessmentId;
        private BigDecimal priceYuanPerKwh;
        private BigDecimal deductionYuan;
        private SharingRuleRequest rule;
        private String requestId;

        public String getAssessmentId() {
            return assessmentId;
        }

        public void setAssessmentId(String assessmentId) {
            this.assessmentId = assessmentId;
        }

        public BigDecimal getPriceYuanPerKwh() {
            return priceYuanPerKwh;
        }

        public void setPriceYuanPerKwh(BigDecimal priceYuanPerKwh) {
            this.priceYuanPerKwh = priceYuanPerKwh;
        }

        public BigDecimal getDeductionYuan() {
            return deductionYuan;
        }

        public void setDeductionYuan(BigDecimal deductionYuan) {
            this.deductionYuan = deductionYuan;
        }

        public SharingRuleRequest getRule() {
            return rule;
        }

        public void setRule(SharingRuleRequest rule) {
            this.rule = rule;
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }

    /** 分账规则请求体：三方比例（必填）+ 规则版本（可选，缺省教学默认） */
    public static final class SharingRuleRequest {
        private BigDecimal platformRate;
        private BigDecimal operatorRate;
        private BigDecimal stationRate;
        private String ruleVersion;

        public BigDecimal getPlatformRate() {
            return platformRate;
        }

        public void setPlatformRate(BigDecimal platformRate) {
            this.platformRate = platformRate;
        }

        public BigDecimal getOperatorRate() {
            return operatorRate;
        }

        public void setOperatorRate(BigDecimal operatorRate) {
            this.operatorRate = operatorRate;
        }

        public BigDecimal getStationRate() {
            return stationRate;
        }

        public void setStationRate(BigDecimal stationRate) {
            this.stationRate = stationRate;
        }

        public String getRuleVersion() {
            return ruleVersion;
        }

        public void setRuleVersion(String ruleVersion) {
            this.ruleVersion = ruleVersion;
        }
    }

    /** 付款登记请求体：付款登记请求标识（幂等键） */
    public static final class RegisterPaymentRequest {
        private String paymentRequestId;

        public String getPaymentRequestId() {
            return paymentRequestId;
        }

        public void setPaymentRequestId(String paymentRequestId) {
            this.paymentRequestId = paymentRequestId;
        }
    }

    /** 更正请求体：更正请求标识 + 更正后核定电量 + 考核扣款（可选）+ 原因 */
    public static final class CorrectSettlementRequest {
        private String correctionRequestId;
        private Double confirmedResponseKwh;
        private BigDecimal deductionYuan;
        private String reason;

        public String getCorrectionRequestId() {
            return correctionRequestId;
        }

        public void setCorrectionRequestId(String correctionRequestId) {
            this.correctionRequestId = correctionRequestId;
        }

        public Double getConfirmedResponseKwh() {
            return confirmedResponseKwh;
        }

        public void setConfirmedResponseKwh(Double confirmedResponseKwh) {
            this.confirmedResponseKwh = confirmedResponseKwh;
        }

        public BigDecimal getDeductionYuan() {
            return deductionYuan;
        }

        public void setDeductionYuan(BigDecimal deductionYuan) {
            this.deductionYuan = deductionYuan;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
