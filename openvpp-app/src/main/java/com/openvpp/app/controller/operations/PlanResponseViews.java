package com.openvpp.app.controller.operations;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.aggregator.plan.AllocationLine;
import com.openvpp.aggregator.plan.PlanConfirmRecord;
import com.openvpp.dispatch.command.CommandState;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.app.orchestration.PlanDemoRunResult;
import com.openvpp.app.service.PlanDispatchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分级派单接口的响应视图装配 —— 领域对象 → 前端契约 Map（camelCase）。
 *
 * 与 ChargeDrResponseViews 同一口径：响应字段由本类显式列出，领域对象不直接
 * 对外序列化；枚举一律同时给出 code 与中文标签；调节量、绝对目标功率、指令
 * 发送/回执时标分列呈现，互不混写（第 54 篇"下发证据"格的呈现落点）。
 */
final class PlanResponseViews {

    private PlanResponseViews() {
    }

    /** 计划摘要：层级/方向/目标/缺口/证据列齐备，行数随摘要给出 */
    static Map<String, Object> planSummary(AggregatePlan plan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", plan.getPlanId());
        body.put("parentPlanId", plan.getParentPlanId());
        body.put("eventId", plan.getEventId());
        body.put("organizerTenantId", plan.getOrganizerTenantId());
        body.put("level", plan.getLevel().name());
        body.put("version", plan.getVersion());
        body.put("direction", plan.getDirection().name());
        body.put("directionLabel", plan.getDirection().getLabel());
        body.put("adjustKw", plan.getAdjustKw());
        body.put("baselineKw", plan.getBaselineKw());
        body.put("gapKw", plan.getGapKw());
        body.put("dispatchable", plan.isDispatchable());
        PlanConfirmRecord confirmation = plan.getConfirmation();
        body.put("confirmedBy", confirmation == null ? null : confirmation.getConfirmer());
        body.put("confirmedAt", confirmation == null ? null : confirmation.getConfirmedAt());
        body.put("sentAt", plan.getSentAt());
        body.put("ackedAt", plan.getAckedAt());
        body.put("createdBy", plan.getCreatedBy());
        body.put("createdAt", plan.getCreatedAt());
        body.put("lineCount", plan.getLines().size());
        return body;
    }

    /** 计划详情：摘要 + 分配行 + （顶层的）子计划摘要 + 版本历史 */
    static Map<String, Object> planDetail(AggregatePlan plan, PlanDispatchService dispatchService) {
        Map<String, Object> body = planSummary(plan);
        body.put("lines", plan.getLines().stream()
                .map(PlanResponseViews::allocationLine)
                .collect(Collectors.toList()));
        if (plan.getParentPlanId() == null) {
            body.put("children", dispatchService.childrenOf(plan.getPlanId()).stream()
                    .map(PlanResponseViews::planSummary)
                    .collect(Collectors.toList()));
        }
        body.put("versions", dispatchService.versionsOf(plan.getPlanId()).stream()
                .map(version -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("version", version.getVersion());
                    item.put("adjustKw", version.getAdjustKw());
                    item.put("gapKw", version.getGapKw());
                    PlanConfirmRecord confirmation = version.getConfirmation();
                    item.put("confirmedBy", confirmation == null ? null : confirmation.getConfirmer());
                    item.put("confirmedAt", confirmation == null ? null : confirmation.getConfirmedAt());
                    item.put("sentAt", version.getSentAt());
                    item.put("ackedAt", version.getAckedAt());
                    item.put("createdBy", version.getCreatedBy());
                    item.put("createdAt", version.getCreatedAt());
                    return item;
                })
                .collect(Collectors.toList()));
        return body;
    }

    /** 分配行视图：一级行（运营商）无目标功率/快照版本，二级行（桩）分列留痕 */
    static Map<String, Object> allocationLine(AllocationLine line) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectId", line.getSubjectId());
        body.put("stationId", line.getStationId());
        body.put("adjustKw", line.getAdjustKw());
        body.put("baselineKw", line.getBaselineKw());
        body.put("targetPowerKw", line.getTargetPowerKw());
        body.put("snapshotVersion", line.getSnapshotVersion());
        return body;
    }

    /**
     * 指令证据视图：状态 / 发送时标 / 回执时标分列 —— 发送证据（平台侧 sentAt）、
     * 受理证据（设备侧 receiptAckedAtMs + receiptStatus）永不混写；
     * 无回执（NO_RECEIPT）即进入核查的待处置证据，不回填任何回执字段。
     */
    static Map<String, Object> command(DispatchCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandNo", command.getCommandNo());
        body.put("planId", command.getPlanId());
        body.put("planVersion", command.getPlanVersion());
        body.put("resourceId", command.getResourceId());
        body.put("stationId", command.getStationId());
        body.put("adjustKw", command.getAdjustKw());
        body.put("baselineKw", command.getBaselineKw());
        body.put("targetPowerKw", command.getTargetPowerKw());
        body.put("originalCommandNo", command.getOriginalCommandNo());
        body.put("state", command.getState().name());
        body.put("createdAt", command.getCreatedAt());
        body.put("sentAt", command.getSentAt());
        body.put("receiptAckedAtMs", command.getReceiptAckedAtMs());
        body.put("receiptStatus", command.getReceiptStatus());
        body.put("rejectReason", command.getRejectReason());
        return body;
    }

    /** 下发结果视图：计划详情 + 指令清单 + 受理统计（失联/拒绝计数显性） */
    static Map<String, Object> sendResult(PlanDispatchService.SendResult result,
                                          PlanDispatchService dispatchService) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", planDetail(result.getPlan(), dispatchService));
        List<Map<String, Object>> commands = result.getCommands().stream()
                .map(PlanResponseViews::command)
                .collect(Collectors.toList());
        body.put("commands", commands);
        long acked = result.getCommands().stream()
                .filter(c -> c.getState() == CommandState.ACKED).count();
        long rejected = result.getCommands().stream()
                .filter(c -> c.getState() == CommandState.REJECTED).count();
        long noReceipt = result.getCommands().stream()
                .filter(c -> c.getState() == CommandState.NO_RECEIPT).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("commandCount", result.getCommands().size());
        summary.put("sentPlans", result.getSentPlans().size());
        summary.put("acked", acked);
        summary.put("rejected", rejected);
        summary.put("noReceipt", noReceipt);
        body.put("summary", summary);
        return body;
    }

    /** 演示运行视图：全链路产物一次呈现（计划树 + 指令证据 + 基线冻结数） */
    static Map<String, Object> demoRun(PlanDemoRunResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", result.getRunId());
        body.put("eventId", result.getEventId());
        body.put("direction", result.getDirection());
        body.put("scenario", result.getScenario());
        body.put("planId", result.getPlanId());
        body.put("gapKw", result.getGapKw());
        body.put("baselineFreezes", result.getBaselineFreezes());
        List<Map<String, Object>> children = new ArrayList<>();
        for (AggregatePlan child : result.getSentPlans()) {
            Map<String, Object> item = planSummary(child);
            children.add(item);
        }
        body.put("sentPlans", children);
        body.put("commands", result.getCommands().stream()
                .map(PlanResponseViews::command)
                .collect(Collectors.toList()));
        return body;
    }
}
