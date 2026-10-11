package com.openvpp.app.orchestration;

import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.BaselineFreeze;

import java.util.List;

/**
 * 效果评估演示运行结果 —— 编排产出的可观察摘要（第 56 篇底座）。
 *
 * 与 MonitoringDemoRunResult 同口径：运行标识/事件标识/方向/场景/计数 +
 * 本道工序的两类证据载体（冻结记录 + 评估记录），供接口视图显式呈现。
 */
public final class AssessmentDemoRunResult {

    private final String runId;
    private final String eventId;
    private final String declarationId;
    private final String direction;
    private final String scenario;
    private final int samplesIngested;
    private final int gapsDetected;
    private final List<BaselineFreeze> freezes;
    private final List<AssessmentRecord> assessments;

    public AssessmentDemoRunResult(String runId, String eventId, String declarationId,
                                   String direction, String scenario,
                                   int samplesIngested, int gapsDetected,
                                   List<BaselineFreeze> freezes, List<AssessmentRecord> assessments) {
        this.runId = runId;
        this.eventId = eventId;
        this.declarationId = declarationId;
        this.direction = direction;
        this.scenario = scenario;
        this.samplesIngested = samplesIngested;
        this.gapsDetected = gapsDetected;
        this.freezes = List.copyOf(freezes);
        this.assessments = List.copyOf(assessments);
    }

    public String getRunId() {
        return runId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getDeclarationId() {
        return declarationId;
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

    public List<BaselineFreeze> getFreezes() {
        return freezes;
    }

    public List<AssessmentRecord> getAssessments() {
        return assessments;
    }
}
