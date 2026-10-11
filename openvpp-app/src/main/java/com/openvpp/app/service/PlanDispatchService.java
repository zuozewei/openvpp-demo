package com.openvpp.app.service;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.aggregator.plan.AggregatePlanService;
import com.openvpp.aggregator.plan.AllocationLine;
import com.openvpp.aggregator.plan.PlanLevel;
import com.openvpp.app.controller.operations.OperationsResourceNotFoundException;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.dispatch.command.ChargePileCommandService;
import com.openvpp.dispatch.command.CommandState;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDeclarationService;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.ChargeDrEventService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分级派单运营门面（第 54 篇接口层底座，第 58 篇轻量后台的服务端依赖）——
 * 把 aggregator.plan / dispatch.command 的库形态能力接到 REST 与演示编排：
 * 计划构建/确认/下发统一入口、计划与指令的查询索引、DataScope 可见性收口。
 *
 * 与库服务的分工：
 * <ol>
 *   <li>构建/确认/发送/回执的业务规则全部在 {@link AggregatePlanService} 与
 *       {@link ChargePileCommandService} 内（口径不变，本类不做第二次实现）；</li>
 *   <li>aggregate/dispatch 模块的内存仓库不支持按数据范围枚举，本类维护
 *       计划/指令查询索引（构建与下发成功后登记，对象引用随库内状态更新，
 *       不产生第二份状态）；</li>
 *   <li>可见性收口：查询与操作先做 DataScope 校验（平台 tenantWide 看本租户，
 *       运营商/场站仅看所属场站参与的计划，空范围短路，越权一律 404 ——
 *       未登记与无权限口径一致，不泄露对象存在性）。</li>
 * </ol>
 *
 * 角色口径（3.5.1 业务链路"平台分配运营商 → 运营商派单到桩"）：平台建计划，
 * 运营商确认与下发。计划服务的平台侧守卫要求 tenantWide 锚定组织方租户 ——
 * 运营商确认/下发时，本层先以其场站范围做可见性校验，再委托计划服务在
 * 组织方租户范围执行（教学演示模型中运营商与平台同属演示租户；与
 * ChargeAssessmentDemoOrchestrator.platformScope() 同一构造路径）。
 *
 * 教学实现单 JVM 内存版（synchronized 串行化），索引与库同进程生命周期。
 */
public class PlanDispatchService {

    private final AggregatePlanService planService;
    private final ChargePileCommandService commandService;
    private final ChargeDrEventService eventService;
    private final ChargeDeclarationService declarationService;
    private final Clock clock;

    /** 计划查询索引：计划标识 → 最新版本对象引用（构建时登记整树） */
    private final Map<String, AggregatePlan> planIndex = new LinkedHashMap<>();
    /** 指令查询索引：指令编号 → 指令对象引用（下发时登记，幂等重试不重复） */
    private final Map<String, DispatchCommand> commandIndex = new LinkedHashMap<>();

    public PlanDispatchService(AggregatePlanService planService,
                               ChargePileCommandService commandService,
                               ChargeDrEventService eventService,
                               ChargeDeclarationService declarationService,
                               Clock clock) {
        this.planService = Objects.requireNonNull(planService, "分级计划服务不能为空");
        this.commandService = Objects.requireNonNull(commandService, "单桩指令服务不能为空");
        this.eventService = Objects.requireNonNull(eventService, "事件服务不能为空");
        this.declarationService = Objects.requireNonNull(declarationService, "申报服务不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    // ---------------- 写路径：构建 / 确认 / 下发 ----------------

    /**
     * 平台构建分级计划：从已确认申报两级分配（事件须已发布且申报已截止，
     * 缺口显性返回但确认/下发被阻止）。申报清单由调用方数据范围汇总传入，
     * 计划服务按事件/状态二次过滤。
     */
    public synchronized AggregatePlan buildPlan(DataScope callerScope, String eventId, String createdBy) {
        return buildPlan(callerScope, eventId, createdBy, LocalDateTime.now(clock));
    }

    /** 显式派单时刻入口：演示编排用于对齐"申报截止即派单"，不经 sleep */
    public synchronized AggregatePlan buildPlan(DataScope callerScope, String eventId,
                                                String createdBy, LocalDateTime at) {
        visibleEvent(callerScope, eventId);
        List<ChargeDeclaration> visible = declarationService.queryDeclarations(callerScope);
        AggregatePlan plan = planService.buildPlan(callerScope, eventId, visible, createdBy, at);
        indexTree(plan);
        return plan;
    }

    /**
     * 人工确认（运营商）：顶层确认代表整树承诺 —— 顶层确认前计划服务校验
     * 全部子计划无缺口（缺口 &gt; 0 即 409，运营三选一处置）；重复确认幂等，
     * 首条确认记录为准。可见性不在当前数据范围即 404。
     */
    public synchronized AggregatePlan confirm(DataScope callerScope, String planId, String confirmer) {
        AggregatePlan plan = requireVisible(callerScope, planId);
        DataScope organizerScope = organizerScopeOf(plan, confirmer);
        LocalDateTime at = LocalDateTime.now(clock);
        AggregatePlan confirmed = planService.confirm(organizerScope, planId, confirmer, at);
        if (plan.getLevel() == PlanLevel.PLATFORM_TO_OPERATOR) {
            // 顶层确认联动确认整树子计划（子计划确认是下发的直接前置，逐棵幂等）
            for (AggregatePlan child : planService.childrenOf(planId)) {
                planService.confirm(organizerScope, child.getPlanId(), confirmer, at);
            }
        }
        return confirmed;
    }

    /**
     * 运营商派单下发：把目标计划的全部二级行经设备通道下发绝对目标功率。
     * 顶层计划下发其全部子计划；子计划仅下发自身。逐桩指令先登记后下发
     * （失联也有发送证据），同编号重复下发幂等拦截；发送/回执时标留痕在
     * 二级（运营商→桩）计划层 —— 顶层计划是分配结果，不经通道、证据列为空；
     * 全部指令受理才记回执，无回执进入核查不记回执。
     *
     * 幂等口径：重复 POST 复用同批指令编号 —— 指令不重发、发送/回执时标不覆盖。
     */
    public synchronized SendResult send(DataScope callerScope, String planId, String operator) {
        AggregatePlan plan = requireVisible(callerScope, planId);
        DataScope organizerScope = organizerScopeOf(plan, operator);
        LocalDateTime at = LocalDateTime.now(clock);
        List<AggregatePlan> targets = sendTargetsOf(plan);
        // 半下发守门：全部目标子计划已人工确认才允许开始投递
        for (AggregatePlan child : targets) {
            if (child.getConfirmation() == null) {
                throw new IllegalStateException("子计划 " + child.getPlanId() + " v" + child.getVersion()
                        + " 未经人工确认，禁止下发（先对计划执行人工确认）");
            }
        }
        List<DispatchCommand> commands = new ArrayList<>();
        List<AggregatePlan> sentPlans = new ArrayList<>();
        for (AggregatePlan child : targets) {
            List<DispatchCommand> childCommands = new ArrayList<>();
            for (AllocationLine line : child.getLines()) {
                // 指令编号 = 计划标识 + 版本 + 资源标识：同版本重试天然幂等，设备侧按编号去重
                String commandNo = child.getPlanId() + "-v" + child.getVersion() + "-" + line.getSubjectId();
                DispatchCommand command = commandService.issue(new DispatchCommand(commandNo,
                        child.getPlanId(), child.getVersion(), line.getSubjectId(), line.getStationId(),
                        line.getAdjustKw(), line.getBaselineKw(), line.getTargetPowerKw(), at));
                commandIndex.put(command.getCommandNo(), command);
                childCommands.add(command);
                commands.add(command);
            }
            planService.markSent(organizerScope, child.getPlanId(), at);
            boolean allAcked = childCommands.stream()
                    .allMatch(c -> c.getState() == CommandState.ACKED);
            if (allAcked) {
                planService.recordReceipt(organizerScope, child.getPlanId(), at);
            }
            sentPlans.add(child);
        }
        return new SendResult(plan, sentPlans, commands);
    }

    // ---------------- 读路径：计划 / 指令查询 ----------------

    /** 计划清单：按当前数据范围过滤（平台全量本租户，运营商/场站仅见所属） */
    public synchronized List<AggregatePlan> queryPlans(DataScope scope) {
        if (scope.isEmpty()) {
            return List.of();
        }
        List<AggregatePlan> matched = new ArrayList<>();
        for (AggregatePlan plan : planIndex.values()) {
            if (visible(scope, plan)) {
                matched.add(plan);
            }
        }
        return List.copyOf(matched);
    }

    /** 计划详情（含整树与版本信息由视图层展开）：不可见即 404 */
    public synchronized AggregatePlan requireVisible(DataScope scope, String planId) {
        AggregatePlan plan;
        try {
            plan = planService.requireLatest(planId);
        } catch (IllegalArgumentException e) {
            throw new OperationsResourceNotFoundException("计划不存在或不在当前数据范围内: " + planId);
        }
        if (!visible(scope, plan)) {
            throw new OperationsResourceNotFoundException("计划不存在或不在当前数据范围内: " + planId);
        }
        return plan;
    }

    /** 计划版本列表（详情视图用，按版本升序） */
    public synchronized List<AggregatePlan> versionsOf(String planId) {
        return planService.versionsOf(planId);
    }

    /** 顶层计划的直接子计划（最新版本） */
    public synchronized List<AggregatePlan> childrenOf(String parentPlanId) {
        return planService.childrenOf(parentPlanId);
    }

    /** 指令证据查询：状态/发送/回执时标分列；planId、state 过滤可选 */
    public synchronized List<DispatchCommand> queryCommands(DataScope scope, String planId, String state) {
        if (scope.isEmpty()) {
            return List.of();
        }
        Set<String> planFilter = null;
        if (planId != null && !planId.isBlank()) {
            // 顶层计划标识展开其子计划：整树指令一次查全
            planFilter = new LinkedHashSet<>();
            planFilter.add(planId);
            for (AggregatePlan child : planService.childrenOf(planId)) {
                planFilter.add(child.getPlanId());
            }
        }
        CommandState expected = null;
        if (state != null && !state.isBlank()) {
            try {
                expected = CommandState.valueOf(state.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无法识别的指令状态: " + state
                        + "（可选 CREATED / SENT / ACKED / REJECTED / NO_RECEIPT）");
            }
        }
        List<DispatchCommand> matched = new ArrayList<>();
        for (DispatchCommand command : commandIndex.values()) {
            if (planFilter != null && !planFilter.contains(command.getPlanId())) {
                continue;
            }
            if (expected != null && command.getState() != expected) {
                continue;
            }
            if (!commandVisible(scope, command)) {
                continue;
            }
            matched.add(command);
        }
        return List.copyOf(matched);
    }

    /** 数据范围内的事件取用：不可见即 404（未登记与无权限口径一致） */
    private ChargeDrEvent visibleEvent(DataScope scope, String eventId) {
        return eventService.queryEvents(scope).stream()
                .filter(event -> event.getEventId().equals(eventId))
                .findFirst()
                .orElseThrow(() -> new OperationsResourceNotFoundException(
                        "事件不存在或不在当前数据范围内: " + eventId));
    }

    /** 计划可见性：平台 tenantWide 锚定组织方租户；运营商/场站按所属场站参与关系 */
    private boolean visible(DataScope scope, AggregatePlan plan) {
        if (scope.isEmpty()) {
            return false;
        }
        if (scope.isTenantWide()) {
            return scope.getTenantId().equals(plan.getOrganizerTenantId());
        }
        Set<String> involved = involvedStations(plan);
        for (String stationId : scope.getStationIds()) {
            if (involved.contains(stationId)) {
                return true;
            }
        }
        return false;
    }

    /** 计划涉及的场站集合：子计划取本计划行场站；顶层计划取全部子计划行场站 */
    private Set<String> involvedStations(AggregatePlan plan) {
        Set<String> stations = new LinkedHashSet<>();
        if (plan.getParentPlanId() == null) {
            for (AggregatePlan child : planService.childrenOf(plan.getPlanId())) {
                for (AllocationLine line : child.getLines()) {
                    stations.add(line.getStationId());
                }
            }
        } else {
            for (AllocationLine line : plan.getLines()) {
                stations.add(line.getStationId());
            }
        }
        return stations;
    }

    /** 指令可见性：平台 tenantWide 锚定计划组织方租户；其余按指令归属场站 */
    private boolean commandVisible(DataScope scope, DispatchCommand command) {
        if (scope.isTenantWide()) {
            AggregatePlan plan = planIndex.get(command.getPlanId());
            return plan != null && scope.getTenantId().equals(plan.getOrganizerTenantId());
        }
        return scope.getStationIds().contains(command.getStationId());
    }

    /**
     * 下发目标集合：顶层计划 → 全部子计划（最新版本）；子计划 → 自身。
     * 顶层无子计划（申报为空）即拒绝，无可下发对象。
     */
    private List<AggregatePlan> sendTargetsOf(AggregatePlan plan) {
        if (plan.getParentPlanId() == null) {
            List<AggregatePlan> children = planService.childrenOf(plan.getPlanId());
            if (children.isEmpty()) {
                throw new IllegalStateException("计划 " + plan.getPlanId() + " 无子计划（无已确认申报），无可下发对象");
            }
            return children;
        }
        return List.of(plan);
    }

    /** 构建即登记整树（顶层 + 子计划），保证查询索引与仓库一致 */
    private void indexTree(AggregatePlan top) {
        planIndex.put(top.getPlanId(), top);
        for (AggregatePlan child : planService.childrenOf(top.getPlanId())) {
            planIndex.put(child.getPlanId(), child);
        }
    }

    /**
     * 教学口径：计划服务的平台侧守卫要求 tenantWide 锚定组织方租户 —— 运营商
     * 确认/下发经本路径委托执行（可见性已由调用方数据范围先行校验）。
     */
    private DataScope organizerScopeOf(AggregatePlan plan, String accountId) {
        return DataScopeResolver.resolve(IdentityContext.of(
                accountId, plan.getOrganizerTenantId(), RoleType.PLATFORM_ADMIN, Set.of()));
    }

    /** 下发结果：目标计划（顶层或子计划）+ 已发送子计划 + 本批指令（含幂等重试命中） */
    public static final class SendResult {
        private final AggregatePlan plan;
        private final List<AggregatePlan> sentPlans;
        private final List<DispatchCommand> commands;

        private SendResult(AggregatePlan plan, List<AggregatePlan> sentPlans, List<DispatchCommand> commands) {
            this.plan = plan;
            this.sentPlans = List.copyOf(sentPlans);
            this.commands = List.copyOf(commands);
        }

        public AggregatePlan getPlan() {
            return plan;
        }

        public List<AggregatePlan> getSentPlans() {
            return sentPlans;
        }

        public List<DispatchCommand> getCommands() {
            return commands;
        }
    }
}
