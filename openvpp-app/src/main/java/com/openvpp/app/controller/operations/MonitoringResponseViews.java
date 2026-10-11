package com.openvpp.app.controller.operations;

import com.openvpp.app.orchestration.MonitoringDemoRunResult;
import com.openvpp.dispatch.monitor.AlertEvidence;
import com.openvpp.dispatch.monitor.CommandMonitorLink;
import com.openvpp.dispatch.monitor.DeviationBasis;
import com.openvpp.dispatch.monitor.DirectionalDeviation;
import com.openvpp.dispatch.monitor.ExecutionEvaluation;
import com.openvpp.dispatch.monitor.MonitoredSample;
import com.openvpp.dispatch.monitor.MonitorAlert;
import com.openvpp.dispatch.monitor.MonitorAlertType;
import com.openvpp.dispatch.monitor.SampleGap;
import com.openvpp.dispatch.monitor.SampleQualityMark;
import com.openvpp.dispatch.command.DispatchCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行监测接口的响应视图装配 —— 领域对象 → 前端契约 Map（camelCase）。
 *
 * 与 ChargeDrResponseViews 同一口径：响应字段由本类显式列出，领域对象不直接
 * 序列化；枚举给 code + 中文标签；质量/来源/缺口显式呈现 —— 模拟来源 SIMULATED、
 * 迟到/重复/乱序标记、缺失缺口在前端视图中一目了然（3.5.3"模拟来源、缺失点、
 * 迟到点在给前端的视图里显式呈现"）。
 */
final class MonitoringResponseViews {

    private MonitoringResponseViews() {
    }

    /** 执行评估视图：回执/到位/连续达标三列 + 核查状态 + 偏差 + 曲线与质量 */
    static Map<String, Object> execution(ExecutionEvaluation evaluation,
                                         Long windowStartMs, Long windowEndMs) {
        CommandMonitorLink link = evaluation.getLink();
        DispatchCommand command = link.getCommand();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", link.getEventId());
        body.put("organizerTenantId", link.getOrganizerTenantId());
        body.put("stationId", command.getStationId());
        body.put("resourceId", command.getResourceId());
        body.put("commandNo", command.getCommandNo());
        body.put("planId", command.getPlanId());
        body.put("planVersion", command.getPlanVersion());
        body.put("direction", link.getDirection().name());
        body.put("directionLabel", link.getDirection().getLabel());
        // 证据三列与目标：调节量/基线/绝对目标分列，与第 54 篇指令口径一致
        body.put("adjustKw", command.getAdjustKw());
        body.put("baselineKw", command.getBaselineKw());
        body.put("targetPowerKw", command.getTargetPowerKw());
        body.put("commandState", command.getState().name());
        body.put("sentAt", command.getSentAt());
        body.put("receiptAckedAtMs", command.getReceiptAckedAtMs());
        // 回执 ≠ 到位 ≠ 连续达标：三列分列，互不推导
        body.put("receiptReceived", evaluation.isReceiptReceived());
        body.put("attained", evaluation.isAttained());
        body.put("steadyAttained", evaluation.isSteadyAttained());
        body.put("checkStatus", evaluation.getCheckStatus().name());
        DirectionalDeviation deviation = evaluation.getDeviation();
        body.put("deviationKw", deviation == null ? null : deviation.getDeviationKw());
        body.put("deviationExceeded", deviation == null ? null : deviation.isExceeded());
        body.put("deviationBasis", deviation == null ? null : deviation.getBasis().name());
        body.put("toleranceKw", deviation == null ? null : deviation.getToleranceKw());
        body.put("curve", curve(link, windowStartMs, windowEndMs));
        body.put("gaps", gaps(link));
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (MonitorAlert alert : evaluation.getAlerts()) {
            alerts.add(alert(alert));
        }
        body.put("alerts", alerts);
        return body;
    }

    /** 告警视图：类型/方向/偏差/样点证据/状态五列齐备，确认留痕 */
    static Map<String, Object> alert(MonitorAlert alert) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("alertId", alert.getAlertId());
        body.put("type", alert.getType().name());
        body.put("typeLabel", alert.getType() == MonitorAlertType.DIRECTIONAL_DEVIATION
                ? "方向性超差" : "失联核查");
        body.put("eventId", alert.getEventId());
        body.put("organizerTenantId", alert.getOrganizerTenantId());
        body.put("stationId", alert.getStationId());
        body.put("resourceId", alert.getResourceId());
        body.put("commandNo", alert.getCommandNo());
        body.put("direction", alert.getDirection().name());
        body.put("directionLabel", alert.getDirection().getLabel());
        // NaN（失联无偏差口径）显式转 null，前端按"无偏差"呈现
        body.put("deviationKw", Double.isNaN(alert.getDeviationKw()) ? null : alert.getDeviationKw());
        body.put("basis", alert.getBasis() == null ? null : alert.getBasis().name());
        body.put("basisLabel", basisLabel(alert.getBasis()));
        body.put("toleranceKw", Double.isNaN(alert.getToleranceKw()) ? null : alert.getToleranceKw());
        body.put("evidence", alert.getEvidence() == null ? null : evidence(alert.getEvidence()));
        body.put("state", alert.getState().name());
        body.put("raisedAt", alert.getRaisedAt());
        body.put("confirmedBy", alert.getConfirmedBy());
        body.put("confirmedAt", alert.getConfirmedAt());
        return body;
    }

    /** 演示运行结果视图：编排产出的可观察摘要（评估 + 告警 + 计数） */
    static Map<String, Object> demoRun(MonitoringDemoRunResult run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.getRunId());
        body.put("eventId", run.getEventId());
        body.put("planId", run.getPlanId());
        body.put("direction", run.getDirection());
        body.put("scenario", run.getScenario());
        body.put("samplesIngested", run.getSamplesIngested());
        body.put("gapsDetected", run.getGapsDetected());
        List<Map<String, Object>> evaluations = new ArrayList<>();
        for (ExecutionEvaluation evaluation : run.getEvaluations()) {
            // 演示视图不带时间窗：全量曲线
            evaluations.add(execution(evaluation, null, null));
        }
        body.put("evaluations", evaluations);
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (MonitorAlert alert : run.getAlerts()) {
            alerts.add(alert(alert));
        }
        body.put("alerts", alerts);
        return body;
    }

    // ---------------- 内部 ----------------

    /** 曲线点：采样/接收双时标、来源、质量标记、证据资格、指令前标记显式呈现 */
    private static List<Map<String, Object>> curve(CommandMonitorLink link,
                                                   Long windowStartMs, Long windowEndMs) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (MonitoredSample sample : link.getSamples()) {
            if (windowStartMs != null && sample.getSampledAtMs() < windowStartMs) {
                continue;
            }
            if (windowEndMs != null && sample.getSampledAtMs() > windowEndMs) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("seq", sample.getSeq());
            point.put("sampledAtMs", sample.getSampledAtMs());
            point.put("receivedAtMs", sample.getReceivedAtMs());
            point.put("powerKw", sample.getPowerKw());
            point.put("source", sample.getSource().name());
            List<String> marks = new ArrayList<>();
            for (SampleQualityMark mark : sample.getQualityMarks()) {
                marks.add(mark.name());
            }
            point.put("qualityMarks", marks);
            point.put("evidenceEligible", sample.isEvidenceEligible(link.getSentAtMs()));
            point.put("beforeCommandSent", sample.isBeforeCommandSent(link.getSentAtMs()));
            points.add(point);
        }
        return points;
    }

    /** 缺口视图：缺失不补 0，曲线留缺（前端按缺口段渲染缺失标记） */
    private static List<Map<String, Object>> gaps(CommandMonitorLink link) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (SampleGap gap : link.getGaps()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("fromSeq", gap.getFromSeq());
            view.put("toSeq", gap.getToSeq());
            view.put("missingCount", gap.getMissingCount());
            view.put("afterSampledAtMs", gap.getAfterSampledAtMs());
            view.put("beforeSampledAtMs", gap.getBeforeSampledAtMs());
            views.add(view);
        }
        return views;
    }

    private static Map<String, Object> evidence(AlertEvidence evidence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sampledAtMs", evidence.getSampledAtMs());
        body.put("receivedAtMs", evidence.getReceivedAtMs());
        body.put("powerKw", evidence.getPowerKw());
        return body;
    }

    private static String basisLabel(DeviationBasis basis) {
        if (basis == null) {
            return null;
        }
        return basis == DeviationBasis.ABSOLUTE_ZERO_TARGET ? "绝对偏差（零目标）" : "方向性偏差";
    }
}
