package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.app.orchestration.AssessmentDemoRunResult;
import com.openvpp.app.orchestration.ChargeAssessmentDemoOrchestrator;
import com.openvpp.app.orchestration.MonitoringDemoScenario;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.dispatch.monitor.ChargeExecutionMonitor;
import com.openvpp.dispatch.monitor.CommandMonitorLink;
import com.openvpp.dispatch.monitor.ExecutionEvaluation;
import com.openvpp.dispatch.monitor.SampleGap;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.AssessmentRule;
import com.openvpp.settlement.assess.AssessmentService;
import com.openvpp.settlement.assess.BaselineFreeze;
import com.openvpp.settlement.assess.BaselineFreezeRegistry;
import com.openvpp.settlement.assess.MeteredSample;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 效果评估接口（3.5.3 接口契约第 7 行"评估生成与复核：运营商申请；平台复核"）。
 *
 * 安全边界：
 * 1. 角色 —— 申请评估仅运营商（@RequireRole 服务端拦截，越角色 401），平台复核仅平台，
 *    查询三方均可；数据范围一律由服务端会话解析，平台看本租户，运营商/场站仅看绑定场站，
 *    空范围短路返回空，禁止退化为全量查询；
 * 2. 评估守门 —— 仅已结束事件可申请；基线未冻结的资源拒绝评估（响应前冻结是评估前置）；
 *    同（事件, 场站, 资源）幂等返回原评估记录，重试不重复生成；
 * 3. 越权口径 —— 不在当前数据范围内的评估记录一律 404（未登记与无权限口径一致）；
 * 4. 审计 —— 申请/复核/演示编排均留痕（SUCCESS 与 REJECTED 分列）。
 *
 * 呈现口径：核定响应量（结算依据）与看板估算（不作核定依据）两列分列；
 * MANUAL_REVIEW 的待复核原因逐条呈现，不把缺证据包装成核定成功。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsAssessmentController {

    public static final String ACTION_ASSESSMENT_APPLY = "ASSESSMENT_APPLY";
    public static final String ACTION_ASSESSMENT_REVIEW = "ASSESSMENT_REVIEW";
    public static final String ACTION_DEMO_RUN = "ASSESSMENT_DEMO_RUN";
    public static final String TARGET_ASSESSMENT = "CHARGE_ASSESSMENT";
    public static final String TARGET_DEMO_RUN = "ASSESSMENT_DEMO_RUN";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private final AssessmentService assessmentService;
    private final AssessmentRule assessmentRule;
    private final BaselineFreezeRegistry freezeRegistry;
    private final ChargeDrEventService eventService;
    private final ChargeExecutionMonitor monitor;
    private final ChargeAssessmentDemoOrchestrator demoOrchestrator;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OperationsAssessmentController(AssessmentService assessmentService,
                                          AssessmentRule assessmentRule,
                                          BaselineFreezeRegistry freezeRegistry,
                                          ChargeDrEventService eventService,
                                          ChargeExecutionMonitor monitor,
                                          ChargeAssessmentDemoOrchestrator demoOrchestrator,
                                          AuditLogService auditLogService,
                                          Clock clock) {
        this.assessmentService = assessmentService;
        this.assessmentRule = assessmentRule;
        this.freezeRegistry = freezeRegistry;
        this.eventService = eventService;
        this.monitor = monitor;
        this.demoOrchestrator = demoOrchestrator;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 申请效果评估（运营商）：输入已结束事件标识，输出该事件范围内全部资源的
     * 评估记录（核定响应量/规则版本/质量结论/待复核原因）。未冻结基线的资源
     * 跳过；事件无已冻结链路或无监测关联时拒绝（409）。
     */
    @RequireRole(RoleType.OPERATOR)
    @PostMapping("/assessments")
    public List<Map<String, Object>> apply(@RequestBody(required = false) ApplyAssessmentRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        if (request == null || request.getEventId() == null || request.getEventId().isBlank()) {
            throw new IllegalArgumentException("事件标识 eventId 不能为空");
        }
        String eventId = request.getEventId().trim();
        try {
            ChargeDrEvent event = visibleEvent(scope, eventId);
            List<AssessmentRecord> records = applyForEvent(scope, event);
            long manual = records.stream()
                    .filter(r -> r.getConclusion() == com.openvpp.settlement.assess.AssessmentConclusion.MANUAL_REVIEW)
                    .count();
            auditLogService.recordForCurrent(ACTION_ASSESSMENT_APPLY, TARGET_ASSESSMENT, eventId,
                    "-", RESULT_SUCCESS, "评估 " + records.size() + " 条（转复核 " + manual + " 条）");
            return records.stream()
                    .map(AssessmentResponseViews::assessment)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_ASSESSMENT_APPLY, TARGET_ASSESSMENT, eventId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /**
     * 平台复核：人工定性留痕（人/时刻/意见）。不在当前数据范围内一律 404；
     * 重复复核幂等返回首条留痕。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/assessments/{assessmentId}/review")
    public Map<String, Object> review(@PathVariable String assessmentId,
                                      @RequestBody(required = false) ReviewAssessmentRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        DataScope scope = DataScopeResolver.resolve(context);
        // 可见性：不在当前数据范围内的评估记录一律 404（未登记与无权限口径一致）
        assessmentService.query(scope).stream()
                .filter(record -> record.getAssessmentId().equals(assessmentId))
                .findFirst()
                .orElseThrow(() -> new OperationsResourceNotFoundException(
                        "评估记录不存在或不在当前数据范围内: " + assessmentId));
        try {
            boolean transitioned = assessmentService.review(scope, assessmentId,
                    context.getAccountId(), request == null ? null : request.getNote(),
                    LocalDateTime.now(clock));
            if (transitioned) {
                auditLogService.recordForCurrent(ACTION_ASSESSMENT_REVIEW, TARGET_ASSESSMENT,
                        assessmentId, "-", RESULT_SUCCESS,
                        "结论 " + assessmentService.require(assessmentId).getConclusion().getLabel()
                                + " 平台复核留痕");
            }
            return AssessmentResponseViews.assessment(assessmentService.require(assessmentId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_ASSESSMENT_REVIEW, TARGET_ASSESSMENT,
                    assessmentId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 评估记录查询：按当前身份数据范围（事件标识可选过滤） */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/assessments")
    public List<Map<String, Object>> list(@RequestParam(required = false) String eventId) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return assessmentService.query(scope).stream()
                .filter(record -> eventId == null || eventId.isBlank()
                        || record.getEventId().equals(eventId))
                .map(AssessmentResponseViews::assessment)
                .collect(Collectors.toList());
    }

    /**
     * 效果评估演示编排：一次运行走完「事件发布 → 快照 → 申报 → 确认 → 响应前基线冻结
     * → 派单下发 → 模拟遥测 → 事件结束 → 评估（只读冻结基线）」链路（仅平台触发；
     * 场景见 {@link MonitoringDemoScenario}，遥测故障注入口径同第 55 篇）。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/assessments/demo-runs")
    public Map<String, Object> runDemo(@RequestBody(required = false) DemoRunRequest request) {
        MonitoringDemoScenario scenario = parseScenario(request == null ? null : request.getScenario());
        DrDirection direction = parseDirection(request == null ? null : request.getDirection());
        try {
            AssessmentDemoRunResult result = demoOrchestrator.run(scenario, direction);
            auditLogService.recordForCurrent(ACTION_DEMO_RUN, TARGET_DEMO_RUN, result.getRunId(),
                    "-", RESULT_SUCCESS,
                    scenario.name() + " " + result.getDirection() + " 评估 " + result.getAssessments().size() + " 条");
            return AssessmentResponseViews.demoRun(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_DEMO_RUN, TARGET_DEMO_RUN, "-",
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    // ---------------- 内部 ----------------

    /** 数据范围内的事件取用：不可见即 404（未登记与无权限口径一致） */
    private ChargeDrEvent visibleEvent(DataScope scope, String eventId) {
        return eventService.queryEvents(scope).stream()
                .filter(event -> event.getEventId().equals(eventId))
                .findFirst()
                .orElseThrow(() -> new OperationsResourceNotFoundException(
                        "事件不存在或不在当前数据范围内: " + eventId));
    }

    /**
     * 逐执行链路评估：监测样点按第 55 篇证据资格口径映射为结算域计量输入，
     * 未冻结基线的链路跳过；事件无已冻结链路或无监测关联时拒绝。
     */
    private List<AssessmentRecord> applyForEvent(DataScope scope, ChargeDrEvent event) {
        Set<String> stationFilter = scope.isTenantWide() ? null : scope.getStationIds();
        List<ExecutionEvaluation> evaluations = monitor.queryExecutions(
                event.getEventId(), scope.getTenantId(), stationFilter, null);
        if (evaluations.isEmpty()) {
            throw new IllegalStateException("事件无可评估的执行链路（无监测关联）: " + event.getEventId());
        }
        List<AssessmentRecord> records = new ArrayList<>();
        int unfrozen = 0;
        for (ExecutionEvaluation evaluation : evaluations) {
            CommandMonitorLink link = evaluation.getLink();
            String stationId = link.getCommand().getStationId();
            String resourceId = link.getCommand().getResourceId();
            Optional<BaselineFreeze> freeze = freezeRegistry.find(event.getEventId(), stationId, resourceId);
            if (freeze.isEmpty()) {
                unfrozen++;
                continue;   // 基线未冻结的资源拒绝评估（响应前冻结是评估前置）
            }
            List<MeteredSample> metered = link.getSamples().stream()
                    .map(sample -> new MeteredSample(sample.getSampledAtMs(), sample.getPowerKw(),
                            sample.isEvidenceEligible(link.getSentAtMs())))
                    .collect(Collectors.toList());
            long windowStartMs = freeze.get().windowStartMs();
            int missing = Math.toIntExact(link.getGaps().stream()
                    .filter(gap -> gap.getBeforeSampledAtMs() >= windowStartMs)
                    .mapToLong(SampleGap::getMissingCount)
                    .sum());
            records.add(assessmentService.assess(scope, event, stationId, resourceId,
                    metered, missing, assessmentRule, LocalDateTime.now(clock)));
        }
        if (records.isEmpty()) {
            throw new IllegalStateException("事件无已冻结基线的执行链路，拒绝评估（响应前须先完成基线冻结）: "
                    + event.getEventId() + "（未冻结链路 " + unfrozen + " 条）");
        }
        return records;
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

    /** 申请评估请求体：已结束事件标识 */
    public static final class ApplyAssessmentRequest {
        private String eventId;

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }
    }

    /** 复核请求体：复核意见（可选） */
    public static final class ReviewAssessmentRequest {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
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
