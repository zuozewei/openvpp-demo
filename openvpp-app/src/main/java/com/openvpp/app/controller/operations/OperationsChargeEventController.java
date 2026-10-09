package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.DrDirection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 充电桩需求响应事件接口（3.5.3 接口契约第 3 行"事件"：平台建/发/停/授权，三方按范围查询）。
 *
 * 安全边界：
 * 1. 角色 —— 建立/发布/结束/参与授权仅平台（@RequireRole 服务端拦截，越角色 401）；
 *    查询三方角色均可，但数据范围一律由服务端会话解析（DataScopeResolver.resolve(IdentityContextHolder.require())），
 *    平台看本租户事件，运营商/场站仅看已发布且参与授权覆盖的事件，空范围短路返回空；
 * 2. 租户 —— 事件组织方恒为会话租户（organizerTenantId 取 IdentityContext），
 *    请求体中的 tenantId 字段不参与任何判定（伪造无效）；
 * 3. 可见性 —— 详情查询未命中当前数据范围一律 404（未登记与无权限口径一致，不泄露存在性）；
 * 4. 审计 —— 每次写操作留痕（账号/角色/动作/对象/版本/结果，SUCCESS 与 REJECTED 分列）。
 *
 * 参与范围口径：服务层硬规则"仅已发布事件可变更参与范围"，故参与授权在发布之后经
 * /events/{eventId}/participations 显式登记（建立时不预置参与场站）。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsChargeEventController {

    public static final String ACTION_EVENT_CREATE = "EVENT_CREATE";
    public static final String ACTION_EVENT_PUBLISH = "EVENT_PUBLISH";
    public static final String ACTION_EVENT_FINISH = "EVENT_FINISH";
    public static final String ACTION_EVENT_AUTHORIZE = "EVENT_AUTHORIZE";
    public static final String TARGET_EVENT = "CHARGE_DR_EVENT";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private final ChargeDrEventService eventService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OperationsChargeEventController(ChargeDrEventService eventService,
                                           AuditLogService auditLogService,
                                           Clock clock) {
        this.eventService = eventService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 建立事件（CREATED）：方向/响应时间窗/目标调节量/申报截止，事件标识缺省服务端生成。
     * 时间链硬规则（建立 &lt; 截止 &lt; 窗口开始 &lt; 窗口结束）由服务层结构校验兜底。
     */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/events")
    public Map<String, Object> create(@RequestBody CreateEventRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        String eventId = (request.getEventId() == null || request.getEventId().isBlank())
                ? "EVT-" + UUID.randomUUID()
                : request.getEventId().trim();
        try {
            DrDirection direction = parseDirection(request.getDirection());
            ChargeDrEvent event = eventService.create(
                    eventId,
                    context.getTenantId(),
                    direction,
                    requireTime("windowStart", request.getWindowStart()),
                    requireTime("windowEnd", request.getWindowEnd()),
                    requirePositiveKw("targetAdjustKw", request.getTargetAdjustKw()),
                    requireTime("declareDeadline", request.getDeclareDeadline()),
                    LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_EVENT_CREATE, TARGET_EVENT, eventId,
                    String.valueOf(event.getVersion()), RESULT_SUCCESS,
                    direction.getLabel() + " 事件建立");
            return ChargeDrResponseViews.event(event, clock);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_EVENT_CREATE, TARGET_EVENT, eventId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 事件清单：按当前身份数据范围查询（空范围短路返回空，禁止退化为全量） */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/events")
    public List<Map<String, Object>> list() {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return eventService.queryEvents(scope).stream()
                .map(e -> ChargeDrResponseViews.event(e, clock))
                .collect(Collectors.toList());
    }

    /** 事件详情：仅当前数据范围内可见；未登记与无权限统一 404 */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/events/{eventId}")
    public Map<String, Object> detail(@PathVariable String eventId) {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return eventService.queryEvents(scope).stream()
                .filter(e -> e.getEventId().equals(eventId))
                .findFirst()
                .map(e -> ChargeDrResponseViews.event(e, clock))
                .orElseThrow(() -> new OperationsResourceNotFoundException(
                        "事件不存在或不在当前数据范围内: " + eventId));
    }

    /** 发布事件：CREATED → PUBLISHED；申报截止已过即拒绝（409） */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/events/{eventId}/publish")
    public Map<String, Object> publish(@PathVariable String eventId) {
        try {
            ChargeDrEvent event = eventService.publish(
                    organizerScope(), eventId, LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_EVENT_PUBLISH, TARGET_EVENT, eventId,
                    String.valueOf(event.getVersion()), RESULT_SUCCESS, "事件发布");
            return ChargeDrResponseViews.event(event, clock);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_EVENT_PUBLISH, TARGET_EVENT, eventId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 结束事件：PUBLISHED → ENDED；结束后申报入口即关（服务层生命周期守门） */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/events/{eventId}/finish")
    public Map<String, Object> finish(@PathVariable String eventId) {
        try {
            ChargeDrEvent event = eventService.end(
                    organizerScope(), eventId, LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_EVENT_FINISH, TARGET_EVENT, eventId,
                    String.valueOf(event.getVersion()), RESULT_SUCCESS, "事件结束");
            return ChargeDrResponseViews.event(event, clock);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_EVENT_FINISH, TARGET_EVENT, eventId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 参与授权：把场站纳入事件申报范围（显式授权，可批量）；每次授权递增事件版本 */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/events/{eventId}/participations")
    public Map<String, Object> authorize(@PathVariable String eventId,
                                         @RequestBody AuthorizeParticipationRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        List<String> stationIds = request.getStationIds();
        try {
            ChargeDrEvent event = eventService.authorizeParticipation(
                    organizerScope(), eventId, stationIds, context.getAccountId(),
                    LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_EVENT_AUTHORIZE, TARGET_EVENT, eventId,
                    String.valueOf(event.getVersion()), RESULT_SUCCESS,
                    "参与授权场站 " + stationIds);
            return ChargeDrResponseViews.event(event, clock);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_EVENT_AUTHORIZE, TARGET_EVENT, eventId,
                    "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 平台组织方范围：操作类接口的调用方范围守卫（服务层另有租户锚定兜底） */
    private DataScope organizerScope() {
        return DataScopeResolver.resolve(IdentityContextHolder.require());
    }

    /** 调节方向解析：接受枚举名（大小写不敏感）或中文标签（削峰/填谷） */
    private static DrDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("调节方向 direction 不能为空（PEAK_SHAVE/VALLEY_FILL）");
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

    private static LocalDateTime requireTime(String field, LocalDateTime value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static BigDecimal requirePositiveKw(String field, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " 必须为正: " + value);
        }
        return value;
    }

    /** 建立事件请求体：方向/时间窗/目标/截止必填，事件标识可选（缺省服务端生成） */
    public static final class CreateEventRequest {
        private String eventId;
        private String direction;
        private LocalDateTime windowStart;
        private LocalDateTime windowEnd;
        private BigDecimal targetAdjustKw;
        private LocalDateTime declareDeadline;

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public LocalDateTime getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(LocalDateTime windowStart) {
            this.windowStart = windowStart;
        }

        public LocalDateTime getWindowEnd() {
            return windowEnd;
        }

        public void setWindowEnd(LocalDateTime windowEnd) {
            this.windowEnd = windowEnd;
        }

        public BigDecimal getTargetAdjustKw() {
            return targetAdjustKw;
        }

        public void setTargetAdjustKw(BigDecimal targetAdjustKw) {
            this.targetAdjustKw = targetAdjustKw;
        }

        public LocalDateTime getDeclareDeadline() {
            return declareDeadline;
        }

        public void setDeclareDeadline(LocalDateTime declareDeadline) {
            this.declareDeadline = declareDeadline;
        }
    }

    /** 参与授权请求体：场站清单（非空，元素不得为空） */
    public static final class AuthorizeParticipationRequest {
        private List<String> stationIds;

        public List<String> getStationIds() {
            return stationIds;
        }

        public void setStationIds(List<String> stationIds) {
            this.stationIds = stationIds;
        }
    }
}
