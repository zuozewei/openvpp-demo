package com.openvpp.settlement.charge;

import com.openvpp.common.context.DataScope;
import com.openvpp.resource.org.OrgRegistryService;
import com.openvpp.resource.org.Station;
import com.openvpp.settlement.assess.AssessmentConclusion;
import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.AssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 结算服务 —— 多级分账的办理入口（第 57 篇底座，3.5.3 接口契约第 8 行
 * "结算办理：平台生成、确认、付款登记"；3.5.5"结算与更正"验收格）。
 *
 * 四个入口，全部带数据范围守卫（与评估服务同口径：空范围拒绝退化，
 * tenantWide 锚定租户，场站清单形态须在范围内）：
 * <ol>
 *   <li>生成（平台）：消费 CONFIRMED 评估记录的核定电量 → 四量 → 三方分账；
 *       请求标识幂等，重复生成返回原单；MANUAL_REVIEW 评估拒绝入结算；</li>
 *   <li>确认（平台）：并发确认幂等（首条留痕为准），旧版本不覆盖；</li>
 *   <li>付款登记（平台）：仅登记模拟结果（SIMULATED 渠道），不接真实资金通道；
 *       请求标识重试幂等，不同请求重复登记拒绝；</li>
 *   <li>更正（平台）：更正请求标识幂等；前后版本、差额、操作主体、时间、原因齐备；
 *       更正生成新版本单据，历史版本留存可查，差额可复算；已登记付款的单据禁止更正。</li>
 * </ol>
 *
 * 分账主体标识按归属链解析（平台 = 组织方租户；运营商 = 场站归属运营主体；
 * 场站 = 评估场站），不允许请求方自填 —— 三级档案由 openvpp-resource 强制
 * 归属一致，结算域只读引用。跨租户更正与越权确认由本服务 DataScope 守卫 +
 * controller 层 @RequireRole 双重拦截。
 *
 * 教学实现为单 JVM 内存版（synchronized 串行化），多次运行按请求标识幂等隔离；
 * 分布式部署时幂等键与版本链需换外部唯一约束，口径不变。
 */
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final AssessmentService assessmentService;
    private final OrgRegistryService orgRegistryService;

    /** 结算单标识 → 版本链（按账单版本升序，历史版本永久留存） */
    private final Map<String, List<SettlementOrder>> versionsBySettlementId = new LinkedHashMap<>();
    /** 生成请求标识 → 结算单标识：生成幂等键 */
    private final Map<String, String> settlementIdByRequestId = new LinkedHashMap<>();
    /** 更正请求标识 → 更正记录：更正幂等键 */
    private final Map<String, CorrectionRecord> correctionByRequestId = new LinkedHashMap<>();
    /** 付款登记请求标识 → 付款记录：付款幂等键 */
    private final Map<String, PaymentRecord> paymentByRequestId = new LinkedHashMap<>();
    /** 结算单标识 → 付款登记记录（按登记时刻升序） */
    private final Map<String, List<PaymentRecord>> paymentsBySettlementId = new LinkedHashMap<>();

    public SettlementService(AssessmentService assessmentService, OrgRegistryService orgRegistryService) {
        this.assessmentService = Objects.requireNonNull(assessmentService, "评估服务不能为空");
        this.orgRegistryService = Objects.requireNonNull(orgRegistryService, "主体登记簿不能为空");
    }

    /**
     * 生成结算单（平台办理，请求标识幂等）。
     *
     * @param callerScope        调用方数据范围（须为平台 tenantWide 且锚定评估组织方租户）
     * @param assessmentId       评估标识（仅 CONFIRMED 结论入结算）
     * @param priceYuanPerKwh    补偿单价（元/kWh，教学假设输入，须为正）
     * @param deductionYuan      考核扣款（元，≥ 0 且 ≤ 毛额；null 视为 0）
     * @param rule               分账规则快照（比例总和 = 1，构造即校验）
     * @param requestId          生成请求标识（幂等键，重试返回原单）
     * @param createdBy          制单人（身份上下文账号，服务端取得）
     * @param at                 制单时刻
     * @return 结算单（幂等命中返回原单当前最新版本）
     */
    public synchronized SettlementOrder create(DataScope callerScope, String assessmentId,
                                               BigDecimal priceYuanPerKwh, BigDecimal deductionYuan,
                                               SharingRuleSnapshot rule, String requestId,
                                               String createdBy, LocalDateTime at) {
        Objects.requireNonNull(at, "制单时刻不能为空");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("生成请求标识 requestId 不能为空");
        }
        // 范围与结算守门先于幂等命中：跨租户/越角色重放请求标识同样被拒，不泄露原单
        AssessmentRecord assessment = requireSettleable(callerScope, assessmentId);
        String idempotentHit = settlementIdByRequestId.get(requestId);
        if (idempotentHit != null) {
            SettlementOrder existing = latest(idempotentHit);
            log.info("结算生成幂等命中: requestId {} 返回原结算单 {}（账单版本 v{}）",
                    requestId, idempotentHit, existing.getBillVersion());
            return existing;
        }
        Station station = resolveStationWithinTenant(assessment);

        BigDecimal price = requirePositive(priceYuanPerKwh, "补偿单价");
        BigDecimal deduction = normalizeDeduction(deductionYuan);
        BigDecimal gross = SettlementCalculator.grossYuan(assessment.getConfirmedResponseKwh(), price);
        if (deduction.compareTo(gross) > 0) {
            throw new IllegalStateException("考核扣款不得超过补偿毛额: 扣款 " + deduction
                    + " > 毛额 " + gross + "（超额部分走线下追偿，不入本单）");
        }
        BigDecimal net = SettlementCalculator.netYuan(gross, deduction);
        List<AllocationLine> lines = buildLines(net, rule, assessment, station);

        String settlementId = "STL-" + UUID.randomUUID().toString().substring(0, 8);
        SettlementOrder order = new SettlementOrder(settlementId, 1,
                assessment.getEventId(), assessment.getOrganizerTenantId(),
                assessment.getStationId(), assessment.getResourceId(),
                assessment.getAssessmentId(), assessment.getRuleVersion(), assessment.getBaselineVersion(),
                assessment.getConfirmedResponseKwh(), price, gross, deduction, net,
                rule, lines, createdBy, at);
        versionsBySettlementId.computeIfAbsent(settlementId, key -> new ArrayList<>()).add(order);
        settlementIdByRequestId.put(requestId, settlementId);
        log.info("结算单生成: {} v{} 事件 {} {}@{} 评估 {} 核定 {} kWh × {} 元/kWh"
                        + " = 毛额 {} − 扣款 {} = 净实收 {}（规则快照 {}，请求标识 {}）",
                settlementId, order.getBillVersion(), order.getEventId(), order.getResourceId(),
                order.getStationId(), assessmentId, assessment.getConfirmedResponseKwh(), price,
                gross, deduction, net, rule.getRuleVersion(), requestId);
        return order;
    }

    /**
     * 确认结算单（平台办理，并发确认幂等，旧版本不覆盖）。
     *
     * @return true=本次完成确认；false=已确认或已登记付款，幂等返回（首条留痕为准）
     */
    public synchronized boolean confirm(DataScope callerScope, String settlementId,
                                        String operator, LocalDateTime at) {
        Objects.requireNonNull(at, "确认时刻不能为空");
        requirePlatformScope(callerScope);
        SettlementOrder latest = requireLatestWithinScope(callerScope, settlementId);
        if (latest.getStatus() != SettlementStatus.CREATED) {
            log.info("结算确认幂等命中: {} v{} 状态 {}，首条留痕为准（{} {}）",
                    settlementId, latest.getBillVersion(), latest.getStatus(),
                    latest.getConfirmedBy(), latest.getConfirmedAt());
            return false;
        }
        latest.markConfirmed(requireOperator(operator), at);
        log.info("结算单确认: {} v{} 净实收 {} 确认人 {} 时刻 {}",
                settlementId, latest.getBillVersion(), latest.getNetYuan(), operator, at);
        return true;
    }

    /**
     * 付款登记（平台办理，仅登记模拟结果，重试幂等）。
     *
     * @param paymentRequestId 付款登记请求标识（幂等键）
     * @return 付款记录（同请求标识重试返回原记录）
     */
    public synchronized PaymentRecord registerPayment(DataScope callerScope, String settlementId,
                                                      String paymentRequestId, String operator,
                                                      LocalDateTime at) {
        Objects.requireNonNull(at, "登记时刻不能为空");
        requirePlatformScope(callerScope);
        if (paymentRequestId == null || paymentRequestId.isBlank()) {
            throw new IllegalArgumentException("付款登记请求标识 paymentRequestId 不能为空");
        }
        // 可见性先于幂等命中：跨租户重放请求标识同样被拒，不泄露付款记录
        SettlementOrder latest = requireLatestWithinScope(callerScope, settlementId);
        PaymentRecord idempotentHit = paymentByRequestId.get(paymentRequestId);
        if (idempotentHit != null) {
            log.info("付款登记幂等命中: paymentRequestId {} 返回原记录（结算单 {} v{}）",
                    paymentRequestId, idempotentHit.getSettlementId(), idempotentHit.getBillVersion());
            return idempotentHit;
        }
        if (latest.getStatus() == SettlementStatus.CREATED) {
            throw new IllegalStateException("结算单未确认，拒绝付款登记: " + settlementId
                    + " v" + latest.getBillVersion());
        }
        if (latest.getStatus() == SettlementStatus.PAID) {
            throw new IllegalStateException("结算单已登记模拟付款，不同请求标识重复登记被拒绝: "
                    + settlementId + "（重试请使用原付款登记请求标识）");
        }
        latest.markPaid();
        PaymentRecord record = new PaymentRecord(paymentRequestId, settlementId,
                latest.getBillVersion(), latest.getNetYuan(), requireOperator(operator), at);
        paymentByRequestId.put(paymentRequestId, record);
        paymentsBySettlementId.computeIfAbsent(settlementId, key -> new ArrayList<>()).add(record);
        log.info("付款登记（模拟）: {} {} v{} 金额 {} 登记人 {}（渠道 SIMULATED，非真实资金通道）",
                paymentRequestId, settlementId, latest.getBillVersion(), latest.getNetYuan(), operator);
        return record;
    }

    /**
     * 更正结算单（平台办理，请求标识幂等，版本链留存，差额可复算）。
     *
     * 更正按原单补偿单价与规则快照重算新版本（规则变更不改已确认历史 ——
     * 快照随单保存，更正仍用原快照）；净实收为 0 时不生成正向收益。
     *
     * @param correctionRequestId 更正请求标识（幂等键，重复提交返回已生成的版本）
     * @param confirmedResponseKwh 更正后的核定响应电量（kWh，≥ 0；迟到计量补录场景）
     * @param deductionYuan        更正后的考核扣款（null 沿用当前版本）
     * @param reason               更正原因（必填，留痕）
     * @return 新版本结算单（幂等命中返回已生成的版本）
     */
    public synchronized SettlementOrder correct(DataScope callerScope, String settlementId,
                                                String correctionRequestId, double confirmedResponseKwh,
                                                BigDecimal deductionYuan, String reason,
                                                String operator, LocalDateTime at) {
        Objects.requireNonNull(at, "更正时刻不能为空");
        requirePlatformScope(callerScope);
        if (correctionRequestId == null || correctionRequestId.isBlank()) {
            throw new IllegalArgumentException("更正请求标识 correctionRequestId 不能为空");
        }
        // 可见性先于幂等命中：跨租户重放请求标识同样被拒，不泄露更正产物
        SettlementOrder current = requireLatestWithinScope(callerScope, settlementId);
        CorrectionRecord idempotentHit = correctionByRequestId.get(correctionRequestId);
        if (idempotentHit != null) {
            SettlementOrder corrected = latest(idempotentHit.getSettlementId());
            log.info("结算更正幂等命中: correctionRequestId {} 返回已生成版本 {} v{}",
                    correctionRequestId, corrected.getSettlementId(), corrected.getBillVersion());
            return corrected;
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("更正原因不能为空（留痕必备）");
        }
        if (confirmedResponseKwh < 0) {
            throw new IllegalArgumentException("更正后的核定响应电量不得为负: " + confirmedResponseKwh);
        }
        if (current.getStatus() == SettlementStatus.PAID) {
            throw new IllegalStateException("已登记模拟付款的结算单禁止更正（防账实不符，须走人工流程）: "
                    + settlementId + " v" + current.getBillVersion());
        }

        BigDecimal deduction = deductionYuan == null
                ? current.getDeductionYuan()
                : normalizeDeduction(deductionYuan);
        BigDecimal gross = SettlementCalculator.grossYuan(confirmedResponseKwh, current.getPriceYuanPerKwh());
        if (deduction.compareTo(gross) > 0) {
            throw new IllegalStateException("考核扣款不得超过补偿毛额: 扣款 " + deduction
                    + " > 毛额 " + gross + "（超额部分走线下追偿，不入本单）");
        }
        BigDecimal net = SettlementCalculator.netYuan(gross, deduction);
        if (net.compareTo(current.getNetYuan()) == 0) {
            throw new IllegalStateException("更正后净实收与当前版本一致，无需更正: " + net);
        }
        AssessmentRecord assessment = assessmentService.require(current.getAssessmentId());
        Station station = resolveStationWithinTenant(assessment);
        List<AllocationLine> lines = buildLines(net, current.getRuleSnapshot(), assessment, station);

        int nextVersion = current.getBillVersion() + 1;
        SettlementOrder corrected = new SettlementOrder(settlementId, nextVersion,
                current.getEventId(), current.getOrganizerTenantId(),
                current.getStationId(), current.getResourceId(),
                current.getAssessmentId(), current.getAssessmentRuleVersion(), current.getBaselineVersion(),
                confirmedResponseKwh, current.getPriceYuanPerKwh(), gross, deduction, net,
                current.getRuleSnapshot(), lines, requireOperator(operator), at);
        versionsBySettlementId.get(settlementId).add(corrected);
        CorrectionRecord record = new CorrectionRecord(correctionRequestId, settlementId,
                current.getBillVersion(), nextVersion,
                net.subtract(current.getNetYuan()).setScale(2, RoundingMode.HALF_UP),
                requireOperator(operator), at, reason);
        correctionByRequestId.put(correctionRequestId, record);
        log.info("结算单更正: {} v{}→v{} 差额 {} 操作人 {} 原因 {}（更正请求 {}）",
                settlementId, current.getBillVersion(), nextVersion, record.getDiffYuan(),
                operator, reason, correctionRequestId);
        return corrected;
    }

    /** 取结算单最新版本：不可见（跨租户/未登记）一律拒绝，调用方映射 404 */
    public synchronized SettlementOrder requireLatest(DataScope callerScope, String settlementId) {
        return requireLatestWithinScope(callerScope, settlementId);
    }

    /** 结算单全版本链（历史版本留存可查；不可见一律拒绝） */
    public synchronized List<SettlementOrder> history(DataScope callerScope, String settlementId) {
        requireLatestWithinScope(callerScope, settlementId);
        return List.copyOf(versionsBySettlementId.get(settlementId));
    }

    /** 按数据范围查结算单（最新版本）：空范围短路，tenantWide 本租户，其余形态仅范围内场站 */
    public synchronized List<SettlementOrder> query(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        List<SettlementOrder> matched = new ArrayList<>();
        for (List<SettlementOrder> versions : versionsBySettlementId.values()) {
            SettlementOrder latest = versions.get(versions.size() - 1);
            if (!scope.getTenantId().equals(latest.getOrganizerTenantId())) {
                continue;
            }
            if (!scope.isTenantWide() && !scope.getStationIds().contains(latest.getStationId())) {
                continue;
            }
            matched.add(latest);
        }
        matched.sort(Comparator.comparing(SettlementOrder::getEventId)
                .thenComparing(SettlementOrder::getStationId)
                .thenComparing(SettlementOrder::getResourceId));
        return List.copyOf(matched);
    }

    /** 更正记录查询（按结算单可见性收窄；审计视角全量留痕） */
    public synchronized List<CorrectionRecord> corrections(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        List<SettlementOrder> visible = query(scope);
        Map<String, Boolean> visibleIds = new LinkedHashMap<>();
        for (SettlementOrder order : visible) {
            visibleIds.put(order.getSettlementId(), Boolean.TRUE);
        }
        List<CorrectionRecord> matched = new ArrayList<>();
        for (CorrectionRecord record : correctionByRequestId.values()) {
            if (visibleIds.containsKey(record.getSettlementId())) {
                matched.add(record);
            }
        }
        return List.copyOf(matched);
    }

    /** 结算单的付款登记记录（按登记时刻升序） */
    public synchronized List<PaymentRecord> paymentsOf(DataScope callerScope, String settlementId) {
        requireLatestWithinScope(callerScope, settlementId);
        return List.copyOf(paymentsBySettlementId.getOrDefault(settlementId, List.of()));
    }

    // ---------------- 内部 ----------------

    /** 结算前置守卫：平台范围 + 租户锚定 + 评估结论 CONFIRMED */
    private AssessmentRecord requireSettleable(DataScope callerScope, String assessmentId) {
        if (assessmentId == null || assessmentId.isBlank()) {
            throw new IllegalArgumentException("评估标识 assessmentId 不能为空");
        }
        requirePlatformScope(callerScope);
        AssessmentRecord assessment = assessmentService.require(assessmentId);
        if (!callerScope.getTenantId().equals(assessment.getOrganizerTenantId())) {
            throw new IllegalStateException("评估不在当前数据范围内（跨租户结算被拒绝）: " + assessmentId);
        }
        if (assessment.getConclusion() != AssessmentConclusion.CONFIRMED) {
            throw new IllegalStateException("仅 CONFIRMED 结论的评估可入结算（当前结论 "
                    + assessment.getConclusion().getLabel() + "，核定电量不入结算）: " + assessmentId);
        }
        return assessment;
    }

    /**
     * 平台办理守卫：生成/确认/付款登记/更正均为平台入口 —— DataScope 形态必须为
     * tenantWide（空范围与场站清单形态一律拒绝），角色由 controller 层 @RequireRole 复核，
     * 两层共同构成"DataScope + 角色"双重校验。
     */
    private static void requirePlatformScope(DataScope callerScope) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止办理结算，禁止退化为全量操作");
        }
        if (!callerScope.isTenantWide()) {
            throw new IllegalStateException("仅平台组织方可办理结算（当前范围为场站清单形态）");
        }
    }

    /** 最新版本取用 + 范围守卫：不可见（未登记/跨租户）一律拒绝 */
    private SettlementOrder requireLatestWithinScope(DataScope callerScope, String settlementId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        if (settlementId == null || settlementId.isBlank()) {
            throw new IllegalArgumentException("结算单标识不能为空");
        }
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止操作结算单，禁止退化为全量操作: " + settlementId);
        }
        List<SettlementOrder> versions = versionsBySettlementId.get(settlementId);
        if (versions == null) {
            throw new IllegalArgumentException("未登记结算单: " + settlementId);
        }
        SettlementOrder latest = versions.get(versions.size() - 1);
        if (!callerScope.getTenantId().equals(latest.getOrganizerTenantId())) {
            throw new IllegalStateException("结算单不在当前数据范围内（跨租户操作被拒绝）: " + settlementId);
        }
        if (!callerScope.isTenantWide() && !callerScope.getStationIds().contains(latest.getStationId())) {
            throw new IllegalStateException("结算单不在当前数据范围内: " + settlementId);
        }
        return latest;
    }

    /** 分账主体按归属链解析：平台=组织方租户，运营商=场站归属主体，场站=场站标识 */
    private Station resolveStationWithinTenant(AssessmentRecord assessment) {
        Station station = orgRegistryService.requireStation(assessment.getStationId());
        if (!station.getTenantId().equals(assessment.getOrganizerTenantId())) {
            throw new IllegalStateException("场站归属租户与评估组织方不一致，归属链断裂拒绝结算: "
                    + station.getStationId());
        }
        if (station.getEntityId() == null || station.getEntityId().isBlank()) {
            throw new IllegalStateException("场站未登记归属运营主体，无法确定分账主体: "
                    + station.getStationId());
        }
        return station;
    }

    private List<AllocationLine> buildLines(BigDecimal net, SharingRuleSnapshot rule,
                                            AssessmentRecord assessment, Station station) {
        Map<AllocationParty, BigDecimal> amounts = SettlementCalculator.allocate(net, rule);
        Map<AllocationParty, String> partyIds = new LinkedHashMap<>();
        partyIds.put(AllocationParty.PLATFORM, assessment.getOrganizerTenantId());
        partyIds.put(AllocationParty.OPERATOR, station.getEntityId());
        partyIds.put(AllocationParty.STATION, assessment.getStationId());
        return SettlementCalculator.lines(amounts, partyIds, rule);
    }

    private SettlementOrder latest(String settlementId) {
        List<SettlementOrder> versions = versionsBySettlementId.get(settlementId);
        return versions.get(versions.size() - 1);
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须为正: " + value);
        }
        return value;
    }

    /** 考核扣款归一：null → 0，保留 2 位（HALF_UP 后由毛额上限守卫兜底） */
    private static BigDecimal normalizeDeduction(BigDecimal deductionYuan) {
        if (deductionYuan == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return deductionYuan.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("操作主体不能为空");
        }
        return operator;
    }
}
