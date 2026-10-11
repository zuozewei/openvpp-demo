package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.app.orchestration.ChargeMonitoringDemoOrchestrator;
import com.openvpp.app.orchestration.MonitoringDemoRunResult;
import com.openvpp.app.orchestration.MonitoringDemoScenario;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.dispatch.monitor.AlertState;
import com.openvpp.dispatch.monitor.ChargeExecutionMonitor;
import com.openvpp.dispatch.monitor.ExecutionEvaluation;
import com.openvpp.dispatch.monitor.MonitorAlert;
import com.openvpp.market.charge.DrDirection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 执行监测接口（3.5.3 接口契约第 6 行"执行曲线、告警查询与确认"：各角色按数据范围访问）。
 *
 * 安全边界：
 * 1. 角色 —— 查询与告警确认三方角色均可（@RequireRole 服务端拦截，越角色 401），
 *    数据范围一律由服务端会话解析：平台看本租户全场站，运营商/场站仅看绑定场站，
 *    空范围短路返回空，禁止退化为全量查询；
 * 2. 告警确认 —— 须先过数据范围（不在范围的告警一律 404，未登记与无权限口径一致），
 *    确认动作留审计（账号/角色/对象/结果），重复确认幂等返回首条记录；
 * 3. 演示编排 —— 仅平台触发（@RequireRole PLATFORM_ADMIN），动作留审计；
 * 4. 查询即扫描 —— 两个查询入口先做一次 {@link ChargeExecutionMonitor#scan()}（幂等：
 *    告警按指令/类型/方向去重，确认后冻结），保证迟到样点入库后视图即时反映。
 *
 * 视图呈现口径（3.5.3"模拟来源、缺失点、迟到点在给前端的视图里显式呈现"）：
 * 曲线点带 source（SIMULATED 显式标记）/qualityMarks（LATE/DUPLICATE/OUT_OF_ORDER）/
 * evidenceEligible/beforeCommandSent 列；缺口单列 gaps，缺失不补 0。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsMonitoringController {

    public static final String ACTION_ALERT_CONFIRM = "ALERT_CONFIRM";
    public static final String ACTION_DEMO_RUN = "MONITORING_DEMO_RUN";
    public static final String TARGET_MONITOR_ALERT = "MONITOR_ALERT";
    public static final String TARGET_DEMO_RUN = "MONITORING_DEMO_RUN";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ChargeExecutionMonitor monitor;
    private final ChargeMonitoringDemoOrchestrator demoOrchestrator;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OperationsMonitoringController(ChargeExecutionMonitor monitor,
                                          ChargeMonitoringDemoOrchestrator demoOrchestrator,
                                          AuditLogService auditLogService,
                                          Clock clock) {
        this.monitor = monitor;
        this.demoOrchestrator = demoOrchestrator;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 执行评估查询：事件/场站/桩与时间窗 → 目标/实际曲线点、样点质量、偏差、
     * 达标三列与核查状态。时间窗（ISO-8601，可选）过滤曲线点采样时刻。
     */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/monitoring/executions")
    public List<Map<String, Object>> executions(
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String stationId,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String windowStart,
            @RequestParam(required = false) String windowEnd) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        if (scope.isEmpty()) {
            return List.of();
        }
        monitor.scan();
        Set<String> stationFilter = stationFilterOf(scope);
        Long windowStartMs = parseWindowBound("windowStart", windowStart);
        Long windowEndMs = parseWindowBound("windowEnd", windowEnd);
        return monitor.queryExecutions(eventId, scope.getTenantId(), stationFilter, resourceId).stream()
                .filter(evaluation -> stationId == null || stationId.isBlank()
                        || evaluation.getLink().getCommand().getStationId().equals(stationId))
                .map(evaluation -> MonitoringResponseViews.execution(evaluation, windowStartMs, windowEndMs))
                .collect(Collectors.toList());
    }

    /** 告警查询：事件/状态过滤；各角色仅见本数据范围内的告警 */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/monitoring/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(required = false) String eventId,
                                            @RequestParam(required = false) String state) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        if (scope.isEmpty()) {
            return List.of();
        }
        monitor.scan();
        AlertState alertState = parseAlertState(state);
        Set<String> stationFilter = stationFilterOf(scope);
        return monitor.listAlerts(eventId, scope.getTenantId(), stationFilter, alertState).stream()
                .map(MonitoringResponseViews::alert)
                .collect(Collectors.toList());
    }

    /**
     * 告警人工确认：OPEN → CONFIRMED，留审计。
     * 不在当前数据范围内的告警一律 404（未登记与无权限口径一致，不泄露存在性）；
     * 重复确认幂等返回首条记录（无二次审计）。
     */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @PostMapping("/monitoring/alerts/{alertId}/confirm")
    public Map<String, Object> confirm(@PathVariable String alertId) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        Optional<MonitorAlert> found = scope.isEmpty() ? Optional.empty()
                : monitor.listAlerts(null, scope.getTenantId(), stationFilterOf(scope), null).stream()
                .filter(alert -> alert.getAlertId().equals(alertId))
                .findFirst();
        MonitorAlert alert = found.orElseThrow(() -> new OperationsResourceNotFoundException(
                "告警不存在或不在当前数据范围内: " + alertId));

        boolean transitioned = monitor.confirmAlert(alertId, context.getAccountId(),
                LocalDateTime.now(clock));
        if (transitioned) {
            auditLogService.recordForCurrent(ACTION_ALERT_CONFIRM, TARGET_MONITOR_ALERT,
                    alertId, "-", RESULT_SUCCESS,
                    alert.getType().name() + " " + alert.getDirection().getLabel() + " 告警人工确认");
        }
        return MonitoringResponseViews.alert(alert);
    }

    /**
     * 执行监测演示编排：一次运行走完「计划确认 → 指令下发 → 回执 → 模拟遥测 →
     * 质量判定 → 告警」链路（仅平台触发；场景见 {@link MonitoringDemoScenario}）。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/monitoring/demo-runs")
    public Map<String, Object> runDemo(@RequestBody(required = false) DemoRunRequest request) {
        MonitoringDemoScenario scenario = parseScenario(request == null ? null : request.getScenario());
        DrDirection direction = parseDirection(request == null ? null : request.getDirection());
        try {
            MonitoringDemoRunResult result = demoOrchestrator.run(scenario, direction);
            auditLogService.recordForCurrent(ACTION_DEMO_RUN, TARGET_DEMO_RUN, result.getRunId(),
                    "-", RESULT_SUCCESS,
                    scenario.name() + " " + result.getDirection() + " 样点 " + result.getSamplesIngested());
            return MonitoringResponseViews.demoRun(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_DEMO_RUN, TARGET_DEMO_RUN, "-",
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    // ---------------- 解析辅助 ----------------

    /** 数据范围对应的场站过滤：tenantWide=null（不限场站），场站清单形态=清单本身 */
    private static Set<String> stationFilterOf(DataScope scope) {
        return scope.isTenantWide() ? null : scope.getStationIds();
    }

    private static Long parseWindowBound(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), ISO)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 须为 ISO-8601 本地时间（如 2026-10-09T14:00:00）: " + raw);
        }
    }

    private static AlertState parseAlertState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AlertState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无法识别的告警状态: " + raw + "（可选 OPEN / CONFIRMED）");
        }
    }

    private static MonitoringDemoScenario parseScenario(String raw) {
        if (raw == null || raw.isBlank()) {
            return MonitoringDemoScenario.HEALTHY;
        }
        try {
            return MonitoringDemoScenario.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String candidates = java.util.Arrays.stream(MonitoringDemoScenario.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(" / "));
            throw new IllegalArgumentException("无法识别的演示场景: " + raw + "（可选 " + candidates + "）");
        }
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

    /** 演示运行请求体：场景与方向均可选（缺省 HEALTHY + 场景缺省方向） */
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
