package com.openvpp.app.controller.operations;

import com.openvpp.app.orchestration.AssessmentDemoRunResult;
import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.BaselineFreeze;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效果评估接口的响应视图装配 —— 领域对象 → 前端契约 Map（camelCase）。
 *
 * 与 MonitoringResponseViews 同一口径：响应字段由本类显式列出，领域对象不直接
 * 序列化；枚举给 code + 中文标签；两列数严格分列 —— confirmedResponseKwh 是
 * 结算核定依据，dashboardEstimateKwh 只是看板进度估算（3.5.5"看板估算与
 * 核定量分列"）；待复核原因与复核留痕显式呈现，不把 MANUAL_REVIEW 包装成成功。
 */
final class AssessmentResponseViews {

    private AssessmentResponseViews() {
    }

    /** 评估记录视图：版本三要素（规则/基线/冻结）+ 计量区间 + 质量结论 + 复核留痕 */
    static Map<String, Object> assessment(AssessmentRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessmentId", record.getAssessmentId());
        body.put("eventId", record.getEventId());
        body.put("organizerTenantId", record.getOrganizerTenantId());
        body.put("stationId", record.getStationId());
        body.put("resourceId", record.getResourceId());
        body.put("direction", record.getDirection().name());
        body.put("directionLabel", record.getDirection().getLabel());
        // 追溯三要素：规则版本 + 基线版本 + 冻结时刻/样本来源
        body.put("ruleVersion", record.getRuleVersion());
        body.put("baselineVersion", record.getBaselineVersion());
        body.put("sampleSource", record.getSampleSource());
        body.put("frozenAt", record.getFrozenAt());
        // 计量区间 + 质量结论
        body.put("meteringStartMs", record.getMeteringStartMs());
        body.put("meteringEndMs", record.getMeteringEndMs());
        body.put("eligibleSamples", record.getEligibleSamples());
        body.put("excludedSamples", record.getExcludedSamples());
        body.put("missingSamples", record.getMissingSamples());
        body.put("completenessRatio", record.getCompletenessRatio());
        // 两列数分列：核定响应量（结算依据）≠ 看板估算（不作核定依据）
        body.put("confirmedResponseKwh", record.getConfirmedResponseKwh());
        body.put("dashboardEstimateKwh", record.getDashboardEstimateKwh());
        // 结论与待复核原因显式呈现
        body.put("conclusion", record.getConclusion().name());
        body.put("conclusionLabel", record.getConclusion().getLabel());
        body.put("reviewReasons", new ArrayList<>(record.getReviewReasons()));
        // 复核留痕
        body.put("reviewedBy", record.getReviewedBy());
        body.put("reviewedAt", record.getReviewedAt());
        body.put("reviewNote", record.getReviewNote());
        body.put("createdAt", record.getCreatedAt());
        return body;
    }

    /** 冻结记录视图：冻结时刻/样本来源/规则版本/基线版本/冻结样本齐备 */
    static Map<String, Object> freeze(BaselineFreeze freeze) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", freeze.getEventId());
        body.put("organizerTenantId", freeze.getOrganizerTenantId());
        body.put("stationId", freeze.getStationId());
        body.put("resourceId", freeze.getResourceId());
        body.put("direction", freeze.getDirection().name());
        body.put("directionLabel", freeze.getDirection().getLabel());
        body.put("frozenAt", freeze.getFrozenAt());
        body.put("windowStart", freeze.getWindowStart());
        body.put("windowEnd", freeze.getWindowEnd());
        body.put("sampleSource", freeze.getSampleSource());
        body.put("ruleVersion", freeze.getRuleVersion());
        body.put("baselineVersion", freeze.getBaselineVersion());
        List<Map<String, Object>> points = new ArrayList<>();
        for (var point : freeze.getPoints()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("sampledAtMs", point.getSampledAtMs());
            view.put("powerKw", point.getPowerKw());
            points.add(view);
        }
        body.put("points", points);
        return body;
    }

    /** 演示运行结果视图：编排产出的可观察摘要（冻结 + 评估 + 计数） */
    static Map<String, Object> demoRun(AssessmentDemoRunResult run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.getRunId());
        body.put("eventId", run.getEventId());
        body.put("declarationId", run.getDeclarationId());
        body.put("direction", run.getDirection());
        body.put("scenario", run.getScenario());
        body.put("samplesIngested", run.getSamplesIngested());
        body.put("gapsDetected", run.getGapsDetected());
        List<Map<String, Object>> freezes = new ArrayList<>();
        for (BaselineFreeze freeze : run.getFreezes()) {
            freezes.add(freeze(freeze));
        }
        body.put("freezes", freezes);
        List<Map<String, Object>> assessments = new ArrayList<>();
        for (AssessmentRecord record : run.getAssessments()) {
            assessments.add(assessment(record));
        }
        body.put("assessments", assessments);
        return body;
    }
}
