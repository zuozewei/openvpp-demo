package com.openvpp.aggregator.plan;

import com.openvpp.aggregator.plan.ConstraintAllocator.OperatorCapacity;
import com.openvpp.aggregator.plan.ConstraintAllocator.PileCapacity;
import com.openvpp.common.context.DataScope;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.CapabilitySnapshot;
import com.openvpp.market.charge.DeclarationStatus;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.market.charge.EventLifecycle;
import com.openvpp.market.charge.SnapshotRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 分级计划服务 —— 充电桩需求响应的两级派单编排（第 54 篇底座）。
 *
 * 两级一条链（策划 3.5.1 业务链路"平台分配运营商 → 运营商派单到桩"）：
 * <ol>
 *   <li>一级（平台→运营商）：按已确认申报占比分配，权重 = 申报量、上限 = 有效容量
 *       （每桩 min(申报, 方向可信容量列, 方向可调余量) 封顶后按运营商归并）——
 *       不超申报量、每级另校验可信容量、不越物理边界；</li>
 *   <li>二级（运营商→桩）：份额按桩申报占比再切，逐行做"基线 ± 调节量 → 绝对目标
 *       功率"的方向换算（{@link DirectionalTargetConverter}），目标功率与调节量分列留痕。</li>
 * </ol>
 *
 * 缺口口径：申报不足时草案带缺口显性返回（分配阶段可留缺口），确认阶段要求
 * 合计与目标相等 —— 缺口 &gt; 0 的计划与整树禁止确认/下发，运营三选一：
 * 拒绝事件 / 补申报后重新分配 / 降目标修订计划（见 {@link AggregatePlan#confirm}）。
 *
 * 数据范围：平台侧操作（建计划/确认/修订/记发送/记回执）要求 tenantWide 范围且
 * 锚定事件组织方租户；空范围禁止退化全量操作。申报清单由调用方按各自
 * DataScope 汇总传入（跨租户组装属编排层职责，本服务不做全量查询）。
 *
 * 教学实现为单 JVM 内存版；快照有效性、申报状态机以 market 模块为唯一权威。
 */
public class AggregatePlanService {

    private static final Logger log = LoggerFactory.getLogger(AggregatePlanService.class);

    private final ChargeDrEventService eventService;
    private final SnapshotRegistry snapshotRegistry;
    private final ConstraintAllocator allocator;
    private final AggregatePlanRepository repository;

    public AggregatePlanService(ChargeDrEventService eventService, SnapshotRegistry snapshotRegistry,
                                ConstraintAllocator allocator, AggregatePlanRepository repository) {
        this.eventService = Objects.requireNonNull(eventService, "事件服务不能为空");
        this.snapshotRegistry = Objects.requireNonNull(snapshotRegistry, "快照登记簿不能为空");
        this.allocator = Objects.requireNonNull(allocator, "约束分配器不能为空");
        this.repository = Objects.requireNonNull(repository, "计划仓库不能为空");
    }

    /**
     * 建立分级计划：一级分配（平台→运营商）+ 逐运营商二级分配（运营商→桩）。
     *
     * 闸门：事件须已发布；派单时刻不得早于申报截止（截止守门含时刻本身 ——
     * 截止时刻起申报关闭、可以开始分配）。申报不足时计划带缺口出生，供运营决策，
     * 但确认与下发被阻止。
     *
     * @param callerScope            调用方数据范围（须 tenantWide 且锚定事件组织方租户）
     * @param eventId                事件标识
     * @param confirmedDeclarations  该事件已确认（CONFIRMED）申报清单 —— 调用方按 DataScope
     *                               汇总传入，本服务按事件/状态二次过滤并重新校验快照
     * @param createdBy              创建人
     * @param at                     派单时刻（≥ 申报截止）
     * @return 顶层计划（子计划以 parentPlanId 挂接，经 {@link #childrenOf} 查询）
     */
    public synchronized AggregatePlan buildPlan(DataScope callerScope, String eventId,
                                                List<ChargeDeclaration> confirmedDeclarations,
                                                String createdBy, LocalDateTime at) {
        Objects.requireNonNull(at, "派单时刻 at 不能为空");
        ChargeDrEvent event = requireOrganizerScope(callerScope, eventId);
        if (event.getLifecycle() != EventLifecycle.PUBLISHED) {
            throw new IllegalStateException("仅已发布事件可派单分配: " + eventId);
        }
        if (at.isBefore(event.getDeclareDeadline())) {
            throw new IllegalStateException("申报未截止，不得派单分配: " + eventId
                    + "（截止 " + event.getDeclareDeadline() + "，派单 " + at + "）");
        }
        DrDirection direction = event.getDirection();
        List<ChargeDeclaration> confirmed = filterConfirmed(eventId, confirmedDeclarations);

        // 一级：按运营商租户归并，每桩先封顶（min(申报, 方向可信容量, 方向可调余量)）再求和
        List<OperatorCapacity> operatorCaps = new ArrayList<>();
        Map<String, List<ChargeDeclaration>> byOperator = new LinkedHashMap<>();
        for (ChargeDeclaration declaration : confirmed) {
            byOperator.computeIfAbsent(declaration.getTenantId(), k -> new ArrayList<>()).add(declaration);
        }
        List<String> operatorIds = new ArrayList<>(byOperator.keySet());
        operatorIds.sort(Comparator.naturalOrder());
        for (String operatorId : operatorIds) {
            List<ChargeDeclaration> group = byOperator.get(operatorId);
            group.sort(Comparator.comparing(ChargeDeclaration::getStationId));
            BigDecimal declaredSum = BigDecimal.ZERO;
            BigDecimal effectiveSum = BigDecimal.ZERO;
            BigDecimal baselineSum = BigDecimal.ZERO;
            for (ChargeDeclaration declaration : group) {
                CapabilitySnapshot snapshot = snapshotRegistry.assertUsable(
                        declaration.getStationId(), declaration.getSnapshotVersion(), at);
                BigDecimal effective = effectiveCapacityOf(declaration, snapshot, direction, at);
                declaredSum = declaredSum.add(declaration.getDeclaredKw());
                effectiveSum = effectiveSum.add(effective);
                baselineSum = baselineSum.add(snapshot.getBaselineKw());
            }
            operatorCaps.add(new OperatorCapacity(operatorId, declaredSum, effectiveSum, baselineSum));
        }

        AllocationDraft topDraft = allocator.allocateToOperators(event.getTargetAdjustKw(), direction, operatorCaps);
        String topPlanId = UUID.randomUUID().toString();
        AggregatePlan topPlan = new AggregatePlan(topPlanId, null, eventId,
                event.getOrganizerTenantId(), PlanLevel.PLATFORM_TO_OPERATOR, 1, direction,
                event.getTargetAdjustKw(), totalBaseline(operatorCaps), topDraft.getGapKw(),
                topDraft.getLines(), createdBy, at);
        repository.save(topPlan);

        // 二级：每个运营商份额派生子计划，按桩申报占比再切并换算绝对目标功率
        for (AllocationLine operatorLine : topDraft.getLines()) {
            List<PileCapacity> pileCaps = new ArrayList<>();
            List<ChargeDeclaration> group = byOperator.get(operatorLine.getSubjectId());
            BigDecimal childBaseline = BigDecimal.ZERO;
            for (ChargeDeclaration declaration : group) {
                CapabilitySnapshot snapshot = snapshotRegistry.assertUsable(
                        declaration.getStationId(), declaration.getSnapshotVersion(), at);
                pileCaps.add(new PileCapacity(snapshot.getResourceId(), declaration.getStationId(),
                        declaration.getSnapshotVersion(), declaration.getDeclaredKw(),
                        effectiveCapacityOf(declaration, snapshot, direction, at), snapshot.getBaselineKw()));
                childBaseline = childBaseline.add(snapshot.getBaselineKw());
            }
            AllocationDraft childDraft = allocator.allocateToPiles(
                    operatorLine.getAdjustKw(), direction, pileCaps);
            AggregatePlan childPlan = new AggregatePlan(UUID.randomUUID().toString(), topPlanId, eventId,
                    event.getOrganizerTenantId(), PlanLevel.OPERATOR_TO_PILE, 1, direction,
                    operatorLine.getAdjustKw(), childBaseline, childDraft.getGapKw(),
                    childDraft.getLines(), createdBy, at);
            repository.save(childPlan);
        }

        log.info("分级计划建立: {} 事件 {} {} 目标 {} kW 缺口 {} kW（{} 个运营商子计划）",
                topPlanId, eventId, direction.getLabel(), event.getTargetAdjustKw(),
                topDraft.getGapKw(), topDraft.getLines().size());
        return topPlan;
    }

    /**
     * 人工确认：顶层确认代表整树承诺 —— 除本计划无缺口外，全部直接子计划
     * （最新版本）也必须无缺口，否则拒绝。重复确认幂等，首条记录为准。
     */
    public synchronized AggregatePlan confirm(DataScope callerScope, String planId,
                                              String confirmer, LocalDateTime at) {
        AggregatePlan plan = requireLatestOfScope(callerScope, planId);
        if (plan.getLevel() == PlanLevel.PLATFORM_TO_OPERATOR) {
            for (AggregatePlan child : repository.childrenOf(planId)) {
                if (child.getGapKw().signum() > 0) {
                    throw new IllegalStateException("子计划 " + child.getPlanId() + " 存在分配缺口 "
                            + child.getGapKw() + " kW（运营商 " + child.getLines().stream()
                            .findFirst().map(AllocationLine::getStationId).orElse("-")
                            + " 下属桩吸不满份额），整树确认被拒绝 —— 运营路径三选一：拒绝事件 / 补申报 / 降目标");
                }
            }
        }
        plan.confirm(confirmer, at);
        log.info("计划人工确认: {} v{} 确认人 {} 时刻 {}", planId, plan.getVersion(), confirmer, at);
        return plan;
    }

    /**
     * 修订计划：产生新版本（version + 1），旧版本证据（确认/发送/回执）原样保留。
     * 典型用法 —— 降目标路径：平台修订事件目标后按新目标重切份额；
     * 补申报路径：重新归并申报后重切。子计划须另行修订或重建。
     */
    public synchronized AggregatePlan revisePlan(DataScope callerScope, String planId,
                                                 BigDecimal newAdjustKw, BigDecimal newBaselineKw,
                                                 BigDecimal newGapKw, List<AllocationLine> newLines,
                                                 String revisedBy, LocalDateTime at) {
        AggregatePlan plan = requireLatestOfScope(callerScope, planId);
        AggregatePlan revised = plan.revise(revisedBy, newAdjustKw, newBaselineKw, newGapKw, newLines, at);
        repository.save(revised);
        log.info("计划修订: {} v{} → v{} 修订人 {} 新目标 {} kW 新缺口 {} kW",
                planId, plan.getVersion(), revised.getVersion(), revisedBy, newAdjustKw, newGapKw);
        return revised;
    }

    /** 记录计划版本发送时标（须已确认；幂等不覆盖） */
    public synchronized AggregatePlan markSent(DataScope callerScope, String planId, LocalDateTime at) {
        AggregatePlan plan = requireLatestOfScope(callerScope, planId);
        plan.markSent(at);
        log.info("计划发送留痕: {} v{} 时刻 {}", planId, plan.getVersion(), at);
        return plan;
    }

    /** 记录计划版本回执时标（须已发送；幂等不覆盖；回执 ≠ 达标） */
    public synchronized AggregatePlan recordReceipt(DataScope callerScope, String planId, LocalDateTime at) {
        AggregatePlan plan = requireLatestOfScope(callerScope, planId);
        plan.recordReceipt(at);
        log.info("计划回执留痕: {} v{} 时刻 {}", planId, plan.getVersion(), at);
        return plan;
    }

    public AggregatePlan require(String planId, int version) {
        return repository.require(planId, version);
    }

    public AggregatePlan requireLatest(String planId) {
        return repository.requireLatest(planId);
    }

    public List<AggregatePlan> versionsOf(String planId) {
        return repository.versionsOf(planId);
    }

    /** 顶层计划的直接子计划（每个子计划取最新版本） */
    public List<AggregatePlan> childrenOf(String parentPlanId) {
        return repository.childrenOf(parentPlanId);
    }

    // ---------------- 内部 ----------------

    /** 事件口径过滤：只认本事件、已确认状态的申报（调用方汇总清单的二次守门） */
    private static List<ChargeDeclaration> filterConfirmed(String eventId, List<ChargeDeclaration> candidates) {
        List<ChargeDeclaration> confirmed = new ArrayList<>();
        if (candidates == null) {
            return confirmed;
        }
        for (ChargeDeclaration declaration : candidates) {
            if (eventId.equals(declaration.getEventId())
                    && declaration.getStatus() == DeclarationStatus.CONFIRMED) {
                confirmed.add(declaration);
            }
        }
        confirmed.sort(Comparator.comparing(ChargeDeclaration::getStationId));
        return confirmed;
    }

    /**
     * 单桩有效容量 = min(申报量, 方向可信容量列, 方向可调余量)：
     * 削峰余量 = 基线（压到 0 为界），填谷余量 = 额定 − 基线（充到额为界）。
     * 基线超过额定属快照口径错误，硬拒绝（不允许负余量混进分配）。
     */
    private static BigDecimal effectiveCapacityOf(ChargeDeclaration declaration, CapabilitySnapshot snapshot,
                                                  DrDirection direction, LocalDateTime at) {
        BigDecimal rated = snapshot.getRatedPowerKw();
        BigDecimal baseline = snapshot.getBaselineKw();
        if (baseline.compareTo(rated) > 0) {
            throw new IllegalStateException("能力快照口径不一致（基线超过额定功率），拒绝派单: 场站 "
                    + snapshot.getStationId() + "，基线 " + baseline + " kW，额定 " + rated + " kW");
        }
        BigDecimal credible = snapshot.credibleCapacityFor(direction);
        BigDecimal headroom = direction == DrDirection.PEAK_SHAVE
                ? baseline
                : rated.subtract(baseline);
        BigDecimal effective = declaration.getDeclaredKw().min(credible).min(headroom);
        if (log.isDebugEnabled()) {
            log.debug("桩有效容量: 场站 {} 申报 {} 可信 {} 余量 {} → 有效 {} kW（{}，{}）",
                    snapshot.getStationId(), declaration.getDeclaredKw(), credible, headroom,
                    effective, direction.getLabel(), at);
        }
        return effective;
    }

    private static BigDecimal totalBaseline(List<OperatorCapacity> operatorCaps) {
        return operatorCaps.stream()
                .map(OperatorCapacity::getBaselineKw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 平台侧范围守卫：tenantWide + 锚定事件组织方租户；空范围禁止退化 */
    private ChargeDrEvent requireOrganizerScope(DataScope callerScope, String eventId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        ChargeDrEvent event = eventService.require(eventId);
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止操作计划，禁止退化为全量操作: " + eventId);
        }
        if (!callerScope.isTenantWide()) {
            throw new IllegalStateException("仅平台组织方可执行派单操作（当前范围为场站清单形态）: " + eventId);
        }
        if (!callerScope.getTenantId().equals(event.getOrganizerTenantId())) {
            throw new IllegalStateException("仅事件组织方租户可执行派单操作: " + eventId
                    + "（操作租户 " + callerScope.getTenantId() + "，组织方租户 " + event.getOrganizerTenantId() + "）");
        }
        return event;
    }

    private AggregatePlan requireLatestOfScope(DataScope callerScope, String planId) {
        Objects.requireNonNull(callerScope, "数据范围不能为空");
        AggregatePlan plan = repository.requireLatest(planId);
        if (callerScope.isEmpty()) {
            throw new IllegalStateException("空数据范围禁止操作计划，禁止退化为全量操作: " + planId);
        }
        if (!callerScope.isTenantWide()) {
            throw new IllegalStateException("仅平台组织方可执行本操作（当前范围为场站清单形态）: " + planId);
        }
        if (!callerScope.getTenantId().equals(plan.getOrganizerTenantId())) {
            throw new IllegalStateException("计划不在当前数据范围内: " + planId
                    + "（操作租户 " + callerScope.getTenantId() + "，组织方租户 " + plan.getOrganizerTenantId() + "）");
        }
        return plan;
    }
}
