package com.openvpp.dispatch.strategy;

import com.openvpp.common.enums.ResourceType;
import com.openvpp.common.enums.Scenario;

import java.util.Set;

/**
 * 调度规则 —— 44260"场景决定资源类型"的配置化表达。
 * 每条规则回答一个问题：这个场景允许哪些资源类型参与、约束是什么。
 *
 * 规则是配置而非代码：不同区域的适用规则靠配置隔离，
 * 不靠 if-else 分支——这是第 18 篇区域定制教训的前置解药。
 */
public class DispatchRule {

    private final String ruleId;
    private final Scenario scenario;
    /** 允许参与的资源类型（44260 第 5.9/5.10/5.11 条的"典型资源配置"，原文多为"宜…为主"，非排他准入） */
    private final Set<ResourceType> allowedTypes;
    /** 最大响应时间上限（毫秒），超时的资源类型被排除 */
    private final long maxResponseTimeMs;
    /** 最短持续时长（秒），对应 44260 第 5.9(c)/5.11(b) 的持续时间指标要求 */
    private final long minSustainSeconds;
    /** 优先级：数值小者优先匹配 */
    private final int priority;

    public DispatchRule(String ruleId, Scenario scenario, Set<ResourceType> allowedTypes,
                        long maxResponseTimeMs, long minSustainSeconds, int priority) {
        this.ruleId = ruleId;
        this.scenario = scenario;
        this.allowedTypes = allowedTypes;
        this.maxResponseTimeMs = maxResponseTimeMs;
        this.minSustainSeconds = minSustainSeconds;
        this.priority = priority;
    }

    public String getRuleId() { return ruleId; }
    public Scenario getScenario() { return scenario; }
    public Set<ResourceType> getAllowedTypes() { return allowedTypes; }
    public long getMaxResponseTimeMs() { return maxResponseTimeMs; }
    public long getMinSustainSeconds() { return minSustainSeconds; }
    public int getPriority() { return priority; }
}
