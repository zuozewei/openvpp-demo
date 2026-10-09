package com.openvpp.aggregator.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分级计划仓库 —— 内存版计划版本库（第 54 篇底座）。
 *
 * 存储口径：按（计划标识， 版本）双键保存全部版本 —— 修订不覆盖旧版本，
 * 旧版本的确认/发送/回执证据随版本留存，可逐版本追溯。
 *
 * 教学实现为单 JVM 内存版（synchronized 串行化），与既有内存台账同口径；
 * 分布式部署时计划需落库（版本唯一约束 + 乐观锁），口径不变。
 */
public class AggregatePlanRepository {

    /** 计划标识 → 版本 → 计划 */
    private final Map<String, Map<Integer, AggregatePlan>> byIdVersion = new LinkedHashMap<>();
    /** 父计划标识 → 子计划（按插入顺序） */
    private final Map<String, List<AggregatePlan>> childrenByParent = new LinkedHashMap<>();

    /** 保存一个计划版本（同标识同版本重复保存即拒 —— 版本不可覆盖） */
    public synchronized void save(AggregatePlan plan) {
        Map<Integer, AggregatePlan> versions = byIdVersion.computeIfAbsent(plan.getPlanId(), k -> new LinkedHashMap<>());
        if (versions.putIfAbsent(plan.getVersion(), plan) != null) {
            throw new IllegalStateException("计划版本已存在，禁止覆盖: " + plan.getPlanId() + " v" + plan.getVersion());
        }
        if (plan.getParentPlanId() != null) {
            childrenByParent.computeIfAbsent(plan.getParentPlanId(), k -> new ArrayList<>()).add(plan);
        }
    }

    /** 按标识 + 版本取计划；不存在即抛异常 */
    public synchronized AggregatePlan require(String planId, int version) {
        AggregatePlan plan = byIdVersion.getOrDefault(planId, Map.of()).get(version);
        if (plan == null) {
            throw new IllegalArgumentException("未登记计划: " + planId + " v" + version);
        }
        return plan;
    }

    /** 指定计划的最新版本 */
    public synchronized AggregatePlan requireLatest(String planId) {
        Map<Integer, AggregatePlan> versions = byIdVersion.get(planId);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("未登记计划: " + planId);
        }
        return versions.get(versions.keySet().stream().max(Comparator.naturalOrder()).orElseThrow());
    }

    /** 指定计划的全部版本（按版本升序，不可变视图） */
    public synchronized List<AggregatePlan> versionsOf(String planId) {
        Map<Integer, AggregatePlan> versions = byIdVersion.getOrDefault(planId, Map.of());
        List<AggregatePlan> ordered = new ArrayList<>(versions.values());
        ordered.sort(Comparator.comparingInt(AggregatePlan::getVersion));
        return List.copyOf(ordered);
    }

    /**
     * 指定计划的直接子计划（取每个子计划标识的最新版本，按子计划标识升序）。
     * 父计划修订后旧版本子计划仍挂在该父标识下 —— 确认整树口径时以子计划最新版本为准。
     */
    public synchronized List<AggregatePlan> childrenOf(String parentPlanId) {
        Map<String, AggregatePlan> latestByChildId = new LinkedHashMap<>();
        for (AggregatePlan child : childrenByParent.getOrDefault(parentPlanId, List.of())) {
            latestByChildId.merge(child.getPlanId(), child,
                    (a, b) -> a.getVersion() >= b.getVersion() ? a : b);
        }
        List<AggregatePlan> ordered = new ArrayList<>(latestByChildId.values());
        ordered.sort(Comparator.comparing(AggregatePlan::getPlanId));
        return List.copyOf(ordered);
    }
}
