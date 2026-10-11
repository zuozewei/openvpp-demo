package com.openvpp.app.orchestration;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.dispatch.command.DispatchCommand;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分级派单演示运行结果 —— 一次编排产出的全链路证据（第 54 篇接口层配套）。
 *
 * 与 AssessmentDemoRunResult 同口径：运行标识即事件标识（多次运行按标识隔离），
 * 结果只承载引用（计划树/指令对象的状态由库内对象承载，本类不产生副本）。
 */
public class PlanDemoRunResult {

    private final String runId;
    private final String eventId;
    private final String direction;
    private final String scenario;
    private final String planId;
    private final BigDecimal gapKw;
    private final int baselineFreezes;
    private final List<AggregatePlan> sentPlans;
    private final List<DispatchCommand> commands;

    public PlanDemoRunResult(String runId, String eventId, String direction, String scenario,
                             String planId, BigDecimal gapKw, int baselineFreezes,
                             List<AggregatePlan> sentPlans, List<DispatchCommand> commands) {
        this.runId = runId;
        this.eventId = eventId;
        this.direction = direction;
        this.scenario = scenario;
        this.planId = planId;
        this.gapKw = gapKw;
        this.baselineFreezes = baselineFreezes;
        this.sentPlans = List.copyOf(sentPlans);
        this.commands = List.copyOf(commands);
    }

    public String getRunId() {
        return runId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getDirection() {
        return direction;
    }

    public String getScenario() {
        return scenario;
    }

    public String getPlanId() {
        return planId;
    }

    public BigDecimal getGapKw() {
        return gapKw;
    }

    public int getBaselineFreezes() {
        return baselineFreezes;
    }

    public List<AggregatePlan> getSentPlans() {
        return sentPlans;
    }

    public List<DispatchCommand> getCommands() {
        return commands;
    }
}
