package com.openvpp.app.controller.operations;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.app.orchestration.ChargePlanDemoOrchestrator;
import com.openvpp.app.orchestration.MonitoringDemoScenario;
import com.openvpp.app.orchestration.PlanDemoRunResult;
import com.openvpp.app.service.PlanDispatchService;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.market.charge.DrDirection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分级派单接口（第 54 篇接口层交付，3.5.1 业务链路"平台分配运营商 → 运营商派单到桩"，
 * 第 58 篇轻量后台的派单/指令数据源）。
 *
 * 安全边界：
 * 1. 角色 —— 建计划/演示编排仅平台（@RequireRole 服务端拦截，越角色 401），
 *    人工确认与派单下发仅运营商（缺口 &gt; 0 的计划确认被 409 拒绝），查询三方均可；
 *    数据范围一律由服务端会话解析，平台看本租户，运营商/场站仅看所属场站参与的计划，
 *    空范围短路返回空，禁止退化为全量查询；
 * 2. 缺口守门 —— 申报不足时计划带缺口出生（构建响应显性给出），确认与下发被阻止；
 * 3. 幂等口径 —— 人工确认首条留痕为准；派单下发按"计划标识 + 版本 + 资源标识"生成
 *    指令编号，重复下发幂等拦截、不重发、不覆盖发送/回执时标；
 * 4. 失联口径 —— 设备失联的指令记 NO_RECEIPT 进入核查（无回执 ≠ 设备未执行，
 *    禁止直接重发），计划不回执列留空；
 * 5. 越权口径 —— 不在当前数据范围内的计划/指令一律 404（未登记与无权限口径一致）；
 * 6. 审计 —— 构建/确认/下发/演示编排均留痕（SUCCESS 与 REJECTED 分列，
 *    确认仅在状态迁移时登记，幂等重试不重复留痕）。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsPlanController {

    public static final String ACTION_PLAN_BUILD = "PLAN_BUILD";
    public static final String ACTION_PLAN_CONFIRM = "PLAN_CONFIRM";
    public static final String ACTION_PLAN_SEND = "PLAN_SEND";
    public static final String ACTION_PLAN_DEMO_RUN = "PLAN_DEMO_RUN";
    public static final String TARGET_PLAN = "CHARGE_PLAN";
    public static final String TARGET_PLAN_DEMO_RUN = "PLAN_DEMO_RUN";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private final PlanDispatchService dispatchService;
    private final ChargePlanDemoOrchestrator demoOrchestrator;
    private final AuditLogService auditLogService;

    public OperationsPlanController(PlanDispatchService dispatchService,
                                    ChargePlanDemoOrchestrator demoOrchestrator,
                                    AuditLogService auditLogService) {
        this.dispatchService = dispatchService;
        this.demoOrchestrator = demoOrchestrator;
        this.auditLogService = auditLogService;
    }

    /**
     * 构建分级计划（平台）：输入已发布且申报已截止的事件标识，输出两级分配结果
     * （顶层 = 运营商份额，子计划 = 单桩调节量 + 绝对目标功率）。申报不足时缺口
     * 显性给出，计划不可确认/下发。请求体中的 tenantId 等字段一律被会话身份覆盖。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/plans")
    public Map<String, Object> build(@RequestBody(required = false) BuildPlanRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        if (request == null || request.getEventId() == null || request.getEventId().isBlank()) {
            throw new IllegalArgumentException("事件标识 eventId 不能为空");
        }
        String eventId = request.getEventId().trim();
        try {
            AggregatePlan plan = dispatchService.buildPlan(scope, eventId, context.getAccountId());
            auditLogService.recordForCurrent(ACTION_PLAN_BUILD, TARGET_PLAN, plan.getPlanId(),
                    "v" + plan.getVersion(), RESULT_SUCCESS,
                    "事件 " + eventId + " " + plan.getDirection().getLabel() + " 目标 "
                            + plan.getAdjustKw() + " kW 分配 " + plan.getLines().size() + " 行"
                            + (plan.getGapKw().signum() > 0
                            ? "（缺口 " + plan.getGapKw() + " kW，禁止确认/下发）" : "（缺口 0）"));
            return PlanResponseViews.planDetail(plan, dispatchService);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_PLAN_BUILD, TARGET_PLAN, eventId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /**
     * 人工确认（运营商）：顶层确认代表整树承诺；子计划存在缺口时整树确认被拒绝
     * （409，运营三选一：拒绝事件 / 补申报重新分配 / 降目标修订）。重复确认幂等，
     * 首条确认记录为准，审计不重复留痕。
     */
    @RequireRole(RoleType.OPERATOR)
    @PostMapping("/plans/{planId}/confirm")
    public Map<String, Object> confirm(@PathVariable String planId) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        // 可见性：不在当前数据范围内的计划一律 404（未登记与无权限口径一致）
        AggregatePlan before = dispatchService.requireVisible(scope, planId);
        boolean alreadyConfirmed = before.getConfirmation() != null;
        try {
            AggregatePlan plan = dispatchService.confirm(scope, planId, context.getAccountId());
            if (!alreadyConfirmed) {
                auditLogService.recordForCurrent(ACTION_PLAN_CONFIRM, TARGET_PLAN, planId,
                        "v" + plan.getVersion(), RESULT_SUCCESS,
                        "人工确认留痕（确认人 " + context.getAccountId() + "）");
            }
            return PlanResponseViews.planDetail(plan, dispatchService);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_PLAN_CONFIRM, TARGET_PLAN, planId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /**
     * 派单下发（运营商）：二级行逐桩下发绝对目标功率（先登记后下发，失联也有发送
     * 证据）；记录计划发送/回执时标，全部指令受理才记回执。重复下发幂等拦截，
     * 无回执指令记 NO_RECEIPT 进入核查（禁止直接重发，补发换新编号走服务层 reissue）。
     */
    @RequireRole(RoleType.OPERATOR)
    @PostMapping("/plans/{planId}/send")
    public Map<String, Object> send(@PathVariable String planId) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        dispatchService.requireVisible(scope, planId);
        try {
            PlanDispatchService.SendResult result = dispatchService.send(
                    scope, planId, context.getAccountId());
            Map<String, Object> body = PlanResponseViews.sendResult(result, dispatchService);
            Map<?, ?> summary = (Map<?, ?>) body.get("summary");
            auditLogService.recordForCurrent(ACTION_PLAN_SEND, TARGET_PLAN, planId,
                    "v" + result.getPlan().getVersion(), RESULT_SUCCESS,
                    "下发 " + result.getCommands().size() + " 条指令（受理/拒绝/无回执 = "
                            + summary.get("acked") + "/"
                            + summary.get("rejected") + "/"
                            + summary.get("noReceipt") + "）");
            return body;
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_PLAN_SEND, TARGET_PLAN, planId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 计划清单：按当前身份数据范围（平台全量本租户，运营商/场站仅见所属场站参与的计划） */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/plans")
    public List<Map<String, Object>> list(@RequestParam(required = false) String eventId) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return dispatchService.queryPlans(scope).stream()
                .filter(plan -> eventId == null || eventId.isBlank()
                        || plan.getEventId().equals(eventId))
                .map(PlanResponseViews::planSummary)
                .collect(Collectors.toList());
    }

    /** 计划详情：摘要 + 分配行 + 子计划（顶层时）+ 版本历史；不可见即 404 */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/plans/{planId}")
    public Map<String, Object> detail(@PathVariable String planId) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        AggregatePlan plan = dispatchService.requireVisible(scope, planId);
        return PlanResponseViews.planDetail(plan, dispatchService);
    }

    /**
     * 指令证据查询：状态 / 发送时标 / 回执时标分列，无回执即核查待处置证据；
     * planId、resourceId、state 过滤可选，按当前数据范围收窄。
     */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/commands")
    public List<Map<String, Object>> commands(@RequestParam(required = false) String planId,
                                              @RequestParam(required = false) String resourceId,
                                              @RequestParam(required = false) String state) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return dispatchService.queryCommands(scope, planId, state).stream()
                .filter(command -> resourceId == null || resourceId.isBlank()
                        || command.getResourceId().equals(resourceId))
                .map(PlanResponseViews::command)
                .collect(Collectors.toList());
    }

    /**
     * 分级派单演示编排：一次运行走完「建事件 → 发布 → 授权 → 快照 → 申报 → 确认 →
     * 响应前基线冻结 → 构建计划 → 确认计划 → 下发 → 指令回执」全链路（仅平台触发；
     * 场景仅 HEALTHY / OFFLINE：失联演示指令无回执进入核查，遥测类故障注入见
     * POST /monitoring/demo-runs）。基线冻结早于响应窗口，冻结质量门天然成立。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/plans/demo-runs")
    public Map<String, Object> runDemo(@RequestBody(required = false) DemoRunRequest request) {
        MonitoringDemoScenario scenario = parseScenario(request == null ? null : request.getScenario());
        DrDirection direction = parseDirection(request == null ? null : request.getDirection());
        try {
            PlanDemoRunResult result = demoOrchestrator.run(scenario, direction);
            auditLogService.recordForCurrent(ACTION_PLAN_DEMO_RUN, TARGET_PLAN_DEMO_RUN,
                    result.getRunId(), "-", RESULT_SUCCESS,
                    scenario.name() + " " + result.getDirection() + " 计划 " + result.getPlanId()
                            + " 下发 " + result.getCommands().size() + " 条指令");
            return PlanResponseViews.demoRun(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_PLAN_DEMO_RUN, TARGET_PLAN_DEMO_RUN, "-",
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 场景解析：派单链路只关心设备可达性 —— 健康（受理回执）与失联（无回执进核查）；
     * 遥测质量类场景（偏差/迟到/缺失等）由执行监测演示编排承载，不复用本篇口径。
     */
    private static MonitoringDemoScenario parseScenario(String raw) {
        if (raw == null || raw.isBlank()) {
            return MonitoringDemoScenario.HEALTHY;
        }
        MonitoringDemoScenario scenario;
        try {
            scenario = MonitoringDemoScenario.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无法识别的演示场景: " + raw
                    + "（派单链路可选 HEALTHY / OFFLINE）");
        }
        if (scenario != MonitoringDemoScenario.HEALTHY
                && scenario != MonitoringDemoScenario.OFFLINE) {
            throw new IllegalArgumentException("派单链路演示仅支持 HEALTHY / OFFLINE 场景"
                    + "（遥测类故障注入见 POST /monitoring/demo-runs）: " + raw);
        }
        return scenario;
    }

    /** 调节方向解析：接受枚举名（大小写不敏感）或中文标签（削峰/填谷） */
    private static DrDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        try {
            return DrDirection.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            for (DrDirection direction : DrDirection.values()) {
                if (direction.getLabel().equals(normalized)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("无法识别的调节方向: " + raw
                    + "（可选 PEAK_SHAVE / VALLEY_FILL / 削峰 / 填谷）");
        }
    }

    /** 构建计划请求体：事件标识（tenantId 等字段由会话身份覆盖，伪造无效） */
    public static final class BuildPlanRequest {
        private String eventId;
        private String tenantId;

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    /** 演示运行请求体：场景与方向均可选（缺省 HEALTHY + 削峰） */
    public static final class DemoRunRequest {
        private String scenario;
        private String direction;

        public String getScenario() {
            return scenario;
        }

        public void setScenario(String scenario) {
            this.scenario = scenario;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }
    }
}
