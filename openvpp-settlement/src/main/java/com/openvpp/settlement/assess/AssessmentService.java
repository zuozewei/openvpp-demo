package com.openvpp.settlement.assess;

import com.openvpp.common.context.DataScope;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.EventLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 效果评估服务 —— 冻结基线 + 质量合格样点 → 评估版本的计算入口（第 56 篇底座，
 * 3.5.3 接口契约第 7 行"评估生成与复核：运营商申请；平台复核"）。
 *
 * 三个入口，各自带数据范围守卫（空范围拒绝退化，tenantWide 锚定租户，清单形态须在范围内）：
 * <ol>
 *   <li>申请评估（运营商）：仅已结束事件可评估；基线必须已冻结（评估只读冻结版本）；
 *       同（事件, 场站, 资源）幂等返回原记录，重试不重复生成；</li>
 *   <li>平台复核：对评估记录人工定性留痕（人/时刻/意见），重复复核幂等，首条为准；</li>
 *   <li>按 DataScope 查询：平台看本租户，运营商/场站仅看绑定场站，空范围短路返回空。</li>
 * </ol>
 *
 * 证据不足不硬出结论：采样不足或完整度低于规则阈值 → MANUAL_REVIEW，
 * 待复核原因逐条登记（核定电量仅作参考，不入结算）。
 */
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    private final BaselineFreezeRegistry freezeRegistry;
    private final DirectionalMetering metering;

    /** 评估标识 → 记录 */
    private final Map<String, AssessmentRecord> recordsById = new LinkedHashMap<>();
    /** （事件|场站|资源）→ 评估标识：申请评估幂等键 */
    private final Map<String, String> assessmentIdByKey = new LinkedHashMap<>();

    public AssessmentService(BaselineFreezeRegistry freezeRegistry, DirectionalMetering metering) {
        this.freezeRegistry = Objects.requireNonNull(freezeRegistry, "冻结登记簿不能为空");
        this.metering = Objects.requireNonNull(metering, "方向性计量不能为空");
    }

    /**
     * 申请效果评估（运营商入口）。
     *
     * @param callerScope    调用方数据范围（场站须在其中，租户锚定事件组织方）
     * @param event          已结束事件（未结束一律拒绝）
     * @param stationId      评估场站
     * @param resourceId     评估资源（桩）
     * @param samples        响应期监测样点（结算域中性输入，质量门结论已折算）
     * @param missingSamples 响应窗内缺失样点数（不补 0）
     * @param rule           评估规则
     * @param at             申请时刻
     * @return 评估记录（幂等命中返回原记录）
     */
    public synchronized AssessmentRecord assess(DataScope callerScope, ChargeDrEvent event,
                                                String stationId, String resourceId,
                                                List<MeteredSample> samples, int missingSamples,
                                                AssessmentRule rule, LocalDateTime at) {
        Objects.requireNonNull(event, "事件不能为空");
        Objects.requireNonNull(rule, "评估规则不能为空");
        Objects.requireNonNull(at, "申请时刻不能为空");
        requireStationWithinScope(callerScope, event, stationId);
        if (event.getLifecycle() != EventLifecycle.ENDED) {
            throw new IllegalStateException("仅已结束事件可申请评估: " + event.getEventId()
                    + "（当前生命周期 " + event.getLifecycle() + "）");
        }
        String key = event.getEventId() + "|" + stationId + "|" + resourceId;
        String existingId = assessmentIdByKey.get(key);
        if (existingId != null) {
            AssessmentRecord existing = recordsById.get(existingId);
            log.info("评估幂等命中: {} 返回原评估记录 {}", key, existingId);
            return existing;
        }
        // 评估只读冻结版本：未冻结即拒绝，metering 只接触冻结记录
        BaselineFreeze freeze = freezeRegistry.requireFrozen(event.getEventId(), stationId, resourceId);
        if (freeze.getDirection() != event.getDirection()) {
            throw new IllegalStateException("冻结基线方向与事件方向不一致: 冻结 "
                    + freeze.getDirection() + "，事件 " + event.getDirection());
        }

        MeteringOutcome outcome = metering.meter(freeze, samples == null ? List.of() : samples,
                missingSamples, rule);
        List<String> reasons = new ArrayList<>();
        if (outcome.getEligibleSamples() < rule.getMinEffectiveSamples()) {
            reasons.add("有效样点不足: " + outcome.getEligibleSamples()
                    + " < 规则阈值 " + rule.getMinEffectiveSamples());
        }
        if (outcome.getCompletenessRatio() < rule.getMinCompletenessRatio()) {
            reasons.add("完整度不足: " + String.format(java.util.Locale.ROOT, "%.4f", outcome.getCompletenessRatio())
                    + " < 规则阈值 " + rule.getMinCompletenessRatio());
        }
        AssessmentConclusion conclusion = reasons.isEmpty()
                ? AssessmentConclusion.CONFIRMED
                : AssessmentConclusion.MANUAL_REVIEW;

        AssessmentRecord record = new AssessmentRecord(
                "ASM-" + UUID.randomUUID().toString().substring(0, 8),
                freeze, outcome, conclusion, reasons, at);
        recordsById.put(record.getAssessmentId(), record);
        assessmentIdByKey.put(key, record.getAssessmentId());
        log.info("评估生成: {} 事件 {} {}@{} 方向 {} 核定 {} kWh 完整度 {} 结论 {}（原因 {}）",
                record.getAssessmentId(), event.getEventId(), resourceId, stationId,
                record.getDirection().getLabel(), record.getConfirmedResponseKwh(),
                record.getCompletenessRatio(), conclusion, reasons);
        return record;
    }

    /**
     * 平台复核：人工定性留痕（人/时刻/意见）。不在当前数据范围内的记录一律拒绝；
     * 重复复核幂等返回首条留痕。
     *
     * @param reviewer 复核人（身份上下文账号，服务端取得，不信任请求体角色字段）
     * @return true=本次完成复核留痕；false=已复核过，幂等返回（首条留痕为准）
     */
    public synchronized boolean review(DataScope callerScope, String assessmentId,
                                       String reviewer, String reviewNote, LocalDateTime at) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        Objects.requireNonNull(at, "复核时刻不能为空");
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("复核人不能为空");
        }
        AssessmentRecord record = require(assessmentId);
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止复核评估，禁止退化为全量操作: " + assessmentId);
        }
        if (!callerScope.isTenantWide()) {
            throw new IllegalStateException("仅平台组织方可复核评估（当前范围为场站清单形态）: " + assessmentId);
        }
        if (!callerScope.getTenantId().equals(record.getOrganizerTenantId())) {
            throw new IllegalStateException("评估不在当前数据范围内: " + assessmentId);
        }
        if (record.isReviewed()) {
            log.info("评估复核幂等命中: {} 首条留痕为准（{} {}）", assessmentId,
                    record.getReviewedBy(), record.getReviewedAt());
            return false;
        }
        record.markReviewed(reviewer, at, reviewNote);
        log.info("评估复核留痕: {} 结论 {} 复核人 {} 时刻 {} 意见 {}", assessmentId,
                record.getConclusion(), reviewer, at, reviewNote);
        return true;
    }

    public synchronized AssessmentRecord require(String assessmentId) {
        AssessmentRecord record = recordsById.get(assessmentId);
        if (record == null) {
            throw new IllegalArgumentException("未登记评估记录: " + assessmentId);
        }
        return record;
    }

    /** 按数据范围查评估记录：空范围短路，tenantWide 本租户，其余形态仅范围内场站 */
    public synchronized List<AssessmentRecord> query(DataScope scope) {
        Objects.requireNonNull(scope, "数据范围不能为空");
        if (scope.isEmpty()) {
            return List.of();
        }
        List<AssessmentRecord> matched = new ArrayList<>();
        for (AssessmentRecord record : recordsById.values()) {
            if (!scope.getTenantId().equals(record.getOrganizerTenantId())) {
                continue;
            }
            if (!scope.isTenantWide() && !scope.getStationIds().contains(record.getStationId())) {
                continue;
            }
            matched.add(record);
        }
        matched.sort(Comparator.comparing(AssessmentRecord::getEventId)
                .thenComparing(AssessmentRecord::getStationId)
                .thenComparing(AssessmentRecord::getResourceId));
        return List.copyOf(matched);
    }

    /** 申请评估的范围守卫：空范围拒绝、tenantWide 锚定事件组织方、清单形态须含场站 */
    private static void requireStationWithinScope(DataScope callerScope, ChargeDrEvent event, String stationId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止申请评估，禁止退化为全量操作");
        }
        if (!callerScope.getTenantId().equals(event.getOrganizerTenantId())) {
            throw new IllegalStateException("事件组织方租户不在当前数据范围内: " + event.getOrganizerTenantId());
        }
        if (!callerScope.isTenantWide() && !callerScope.getStationIds().contains(stationId)) {
            throw new IllegalStateException("场站不在当前数据范围内: " + stationId);
        }
    }
}
