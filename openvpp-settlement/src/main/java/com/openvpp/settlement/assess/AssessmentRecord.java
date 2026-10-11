package com.openvpp.settlement.assess;

import com.openvpp.market.charge.DrDirection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 评估记录 —— 一次效果评估的版本化产出（第 56 篇底座，3.5.2"基线快照与评估"行：
 * 样本来源、规则版本、冻结时刻、基线版本、计量区间、完整度、响应量、结论与复核记录）。
 *
 * 记录携带：规则版本 + 基线版本 + 冻结版本 + 计量区间 + 质量结论 + 待复核原因，
 * 争议时按同一版本重算复核。两列数严格分列：核定响应量（结算依据）与
 * 看板估算（过程观察）互不替代。
 *
 * 记录生成后除复核留痕外不可变；同（事件, 场站, 资源）只生成一条（幂等）。
 */
public final class AssessmentRecord {

    private final String assessmentId;
    private final String eventId;
    private final String organizerTenantId;
    private final String stationId;
    private final String resourceId;
    private final DrDirection direction;
    /** 评估规则版本 */
    private final String ruleVersion;
    /** 基线版本（冻结记录标识） */
    private final String baselineVersion;
    private final String sampleSource;
    private final LocalDateTime frozenAt;
    /** 计量区间（首/末有效样点，epoch ms） */
    private final long meteringStartMs;
    private final long meteringEndMs;
    /** 核定响应电量（kWh，结算依据） */
    private final double confirmedResponseKwh;
    /** 看板进度估算（kWh，不作核定依据） */
    private final double dashboardEstimateKwh;
    private final int eligibleSamples;
    private final int excludedSamples;
    private final int missingSamples;
    /** 完整度（有效样点覆盖比例） */
    private final double completenessRatio;
    /** 质量结论：CONFIRMED / MANUAL_REVIEW */
    private final AssessmentConclusion conclusion;
    /** 待复核原因（结论为 CONFIRMED 时为空清单） */
    private final List<String> reviewReasons;
    private final LocalDateTime createdAt;

    /** 平台复核留痕（人工定性；未复核为 null） */
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewNote;

    public AssessmentRecord(String assessmentId, BaselineFreeze freeze, MeteringOutcome outcome,
                            AssessmentConclusion conclusion, List<String> reviewReasons,
                            LocalDateTime createdAt) {
        this.assessmentId = requireText(assessmentId, "评估标识");
        Objects.requireNonNull(freeze, "冻结记录不能为空");
        Objects.requireNonNull(outcome, "计量结果不能为空");
        this.eventId = freeze.getEventId();
        this.organizerTenantId = freeze.getOrganizerTenantId();
        this.stationId = freeze.getStationId();
        this.resourceId = freeze.getResourceId();
        this.direction = freeze.getDirection();
        this.ruleVersion = freeze.getRuleVersion();
        this.baselineVersion = freeze.getBaselineVersion();
        this.sampleSource = freeze.getSampleSource();
        this.frozenAt = freeze.getFrozenAt();
        this.meteringStartMs = outcome.getMeteringStartMs();
        this.meteringEndMs = outcome.getMeteringEndMs();
        this.confirmedResponseKwh = outcome.getConfirmedKwh();
        this.dashboardEstimateKwh = outcome.getDashboardEstimateKwh();
        this.eligibleSamples = outcome.getEligibleSamples();
        this.excludedSamples = outcome.getExcludedSamples();
        this.missingSamples = outcome.getMissingSamples();
        this.completenessRatio = outcome.getCompletenessRatio();
        this.conclusion = Objects.requireNonNull(conclusion, "评估结论不能为空");
        this.reviewReasons = List.copyOf(reviewReasons);
        this.createdAt = Objects.requireNonNull(createdAt, "评估生成时刻不能为空");
    }

    /** 复核留痕（仅平台复核入口可写；重复复核幂等，首条留痕为准） */
    void markReviewed(String reviewer, LocalDateTime at, String note) {
        this.reviewedBy = requireText(reviewer, "复核人");
        this.reviewedAt = Objects.requireNonNull(at, "复核时刻不能为空");
        this.reviewNote = note;
    }

    public boolean isReviewed() {
        return reviewedBy != null;
    }

    public String getAssessmentId() {
        return assessmentId;
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

    public DrDirection getDirection() {
        return direction;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public String getSampleSource() {
        return sampleSource;
    }

    public LocalDateTime getFrozenAt() {
        return frozenAt;
    }

    public long getMeteringStartMs() {
        return meteringStartMs;
    }

    public long getMeteringEndMs() {
        return meteringEndMs;
    }

    public double getConfirmedResponseKwh() {
        return confirmedResponseKwh;
    }

    public double getDashboardEstimateKwh() {
        return dashboardEstimateKwh;
    }

    public int getEligibleSamples() {
        return eligibleSamples;
    }

    public int getExcludedSamples() {
        return excludedSamples;
    }

    public int getMissingSamples() {
        return missingSamples;
    }

    public double getCompletenessRatio() {
        return completenessRatio;
    }

    public AssessmentConclusion getConclusion() {
        return conclusion;
    }

    public List<String> getReviewReasons() {
        return reviewReasons;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    @Override
    public String toString() {
        return "AssessmentRecord{assessmentId=" + assessmentId + ", eventId=" + eventId
                + ", stationId=" + stationId + ", resourceId=" + resourceId
                + ", direction=" + direction + ", ruleVersion=" + ruleVersion
                + ", baselineVersion=" + baselineVersion
                + ", confirmedKwh=" + confirmedResponseKwh
                + ", conclusion=" + conclusion + ", reviewed=" + isReviewed() + '}';
    }
}
