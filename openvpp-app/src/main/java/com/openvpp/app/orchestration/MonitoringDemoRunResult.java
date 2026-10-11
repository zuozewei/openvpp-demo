package com.openvpp.app.orchestration;

import com.openvpp.dispatch.monitor.ExecutionEvaluation;
import com.openvpp.dispatch.monitor.MonitorAlert;

import java.util.List;

/**
 * 执行监测演示运行结果 —— 一次"计划确认 → 指令下发 → 模拟遥测回流 → 监测评估"
 * 编排的完整可观察输出（第 55 篇底座）。
 *
 * 全虚构教学数据：演示桩号、演示计划与演示事件均为本编排自造，
 * 不对应任何真实场站或设备。
 */
public final class MonitoringDemoRunResult {

    private final String runId;
    private final String eventId;
    private final String planId;
    private final String direction;
    private final String scenario;
    private final int samplesIngested;
    private final int gapsDetected;
    private final List<ExecutionEvaluation> evaluations;
    private final List<MonitorAlert> alerts;

    MonitoringDemoRunResult(String runId, String eventId, String planId,
                            String direction, String scenario,
                            int samplesIngested, int gapsDetected,
                            List<ExecutionEvaluation> evaluations, List<MonitorAlert> alerts) {
        this.runId = runId;
        this.eventId = eventId;
        this.planId = planId;
        this.direction = direction;
        this.scenario = scenario;
        this.samplesIngested = samplesIngested;
        this.gapsDetected = gapsDetected;
        this.evaluations = List.copyOf(evaluations);
        this.alerts = List.copyOf(alerts);
    }

    public String getRunId() {
        return runId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getDirection() {
        return direction;
    }

    public String getScenario() {
        return scenario;
    }

    public int getSamplesIngested() {
        return samplesIngested;
    }

    public int getGapsDetected() {
        return gapsDetected;
    }

    public List<ExecutionEvaluation> getEvaluations() {
        return evaluations;
    }

    public List<MonitorAlert> getAlerts() {
        return alerts;
    }
}
