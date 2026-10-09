package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDeclarationService;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 充电桩需求响应申报接口（3.5.3 接口契约第 4 行"申报"：场站提交、运营商确认、场站撤回）。
 *
 * 安全边界：
 * 1. 角色 —— 提交/撤回仅场站运营，确认仅运营商（@RequireRole 服务端拦截，越角色 401）；
 *    查询三方角色均可，数据范围一律由服务端会话解析（平台看本租户申报，
 *    运营商/场站仅看范围内场站申报，空范围短路返回空）；
 * 2. 租户与场站归属 —— 申报锚定租户恒为会话租户，请求体中的 tenantId 字段不参与任何判定；
 *    申报场站必须在调用方数据范围内（服务层守卫，越范围 409）；
 * 3. 服务端拒绝原因 —— 截止守门（含时刻本身）、容量守门、快照失效、未授权场站等
 *    一律经 message 明确返回（409，见 OperationsExceptionAdvice）；
 * 4. 审计 —— 每次写操作留痕（SUCCESS 与 REJECTED 分列），确认/撤回的对象版本随单留痕。
 *
 * 幂等口径：requestId 是提交幂等键，重复请求返回原申报单（不重复校验、不重复占用），
 * 调用方以响应中的 declarationId + requestId 对账。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsChargeDeclarationController {

    public static final String ACTION_DECLARATION_SUBMIT = "DECLARATION_SUBMIT";
    public static final String ACTION_DECLARATION_CONFIRM = "DECLARATION_CONFIRM";
    public static final String ACTION_DECLARATION_WITHDRAW = "DECLARATION_WITHDRAW";
    public static final String TARGET_DECLARATION = "CHARGE_DECLARATION";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private final ChargeDeclarationService declarationService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OperationsChargeDeclarationController(ChargeDeclarationService declarationService,
                                                 AuditLogService auditLogService,
                                                 Clock clock) {
        this.declarationService = declarationService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 提交申报（幂等）：事件 + 场站 + 申报容量 + 快照版本 + 请求标识。
     * 场站须在调用方数据范围内、已获事件参与授权，且申报容量不得超过
     * 所引用快照在事件方向上的可信容量列；服务端守门顺序见 ChargeDeclarationService。
     */
    @RequireRole(RoleType.STATION_OPERATOR)
    @PostMapping("/declarations")
    public Map<String, Object> submit(@RequestBody SubmitDeclarationRequest request) {
        IdentityContext context = IdentityContextHolder.require();
        String requestId = requireText("requestId", request.getRequestId());
        try {
            ChargeDeclaration declaration = declarationService.declare(
                    DataScopeResolver.resolve(context),
                    requireText("eventId", request.getEventId()),
                    requireText("stationId", request.getStationId()),
                    context.getTenantId(),
                    requirePositiveKw("declaredKw", request.getDeclaredKw()),
                    requireText("snapshotVersion", request.getSnapshotVersion()),
                    requestId,
                    LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_DECLARATION_SUBMIT, TARGET_DECLARATION,
                    declaration.getDeclarationId(), "-", RESULT_SUCCESS,
                    "事件 " + declaration.getEventId() + " 场站 " + declaration.getStationId()
                            + " 申报 " + declaration.getDeclaredKw() + " kW");
            return ChargeDrResponseViews.declaration(declaration);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_DECLARATION_SUBMIT, TARGET_DECLARATION,
                    "-", "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 申报清单：按当前身份数据范围查询（空范围短路返回空，禁止退化为全量） */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/declarations")
    public List<Map<String, Object>> list() {
        DataScope scope = DataScopeResolver.resolve(IdentityContextHolder.require());
        return declarationService.queryDeclarations(scope).stream()
                .map(ChargeDrResponseViews::declaration)
                .collect(Collectors.toList());
    }

    /** 运营商确认申报：SUBMITTED → CONFIRMED；仅 CONFIRMED 申报进入后续派单（第 54 篇） */
    @RequireRole(RoleType.OPERATOR)
    @PostMapping("/declarations/{declarationId}/confirm")
    public Map<String, Object> confirm(@PathVariable String declarationId) {
        try {
            ChargeDeclaration declaration = declarationService.confirm(
                    DataScopeResolver.resolve(IdentityContextHolder.require()),
                    declarationId, LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_DECLARATION_CONFIRM, TARGET_DECLARATION,
                    declarationId, "-", RESULT_SUCCESS,
                    "事件 " + declaration.getEventId() + " 场站 " + declaration.getStationId());
            return ChargeDrResponseViews.declaration(declaration);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_DECLARATION_CONFIRM, TARGET_DECLARATION,
                    declarationId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    /** 场站撤回申报：SUBMITTED / CONFIRMED → WITHDRAWN，容量预占即释放（台账留痕） */
    @RequireRole(RoleType.STATION_OPERATOR)
    @PostMapping("/declarations/{declarationId}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String declarationId) {
        try {
            ChargeDeclaration declaration = declarationService.withdraw(
                    DataScopeResolver.resolve(IdentityContextHolder.require()),
                    declarationId, LocalDateTime.now(clock));
            auditLogService.recordForCurrent(ACTION_DECLARATION_WITHDRAW, TARGET_DECLARATION,
                    declarationId, "-", RESULT_SUCCESS,
                    "事件 " + declaration.getEventId() + " 场站 " + declaration.getStationId());
            return ChargeDrResponseViews.declaration(declaration);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_DECLARATION_WITHDRAW, TARGET_DECLARATION,
                    declarationId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static BigDecimal requirePositiveKw(String field, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " 必须为正: " + value);
        }
        return value;
    }

    /** 提交申报请求体：事件/场站/申报容量/快照版本/请求标识必填（tenantId 取会话，入参无效） */
    public static final class SubmitDeclarationRequest {
        private String eventId;
        private String stationId;
        private BigDecimal declaredKw;
        private String snapshotVersion;
        private String requestId;

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getStationId() {
            return stationId;
        }

        public void setStationId(String stationId) {
            this.stationId = stationId;
        }

        public BigDecimal getDeclaredKw() {
            return declaredKw;
        }

        public void setDeclaredKw(BigDecimal declaredKw) {
            this.declaredKw = declaredKw;
        }

        public String getSnapshotVersion() {
            return snapshotVersion;
        }

        public void setSnapshotVersion(String snapshotVersion) {
            this.snapshotVersion = snapshotVersion;
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }
}
