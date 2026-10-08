package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.common.context.RoleType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 操作审计查询入口（平台侧运营能力，3.5.3 契约"审计查询"对应操作码 audit:view）。
 *
 * 越角色访问由 @RequireRole(PLATFORM_ADMIN) 在服务端拦截（401）；
 * 数据范围由 AuditLogRepository 强制：平台角色 = 本租户全量，其余角色 = 本账号
 * （其余角色根本到不了本入口，仓库层口径仍兜底防绕过）。
 * 响应行做列名 camelCase 归一（与 /auth/me 契约同风格），原列值不做任何加工。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsAuditController {

    private final AuditLogService auditLogService;

    public OperationsAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 操作审计查询：平台角色可见本租户全部操作留痕 */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @GetMapping("/audit-logs")
    public List<Map<String, Object>> list() {
        return auditLogService.listForCurrentIdentity().stream()
                .map(OperationsAuditController::camelCase)
                .collect(Collectors.toList());
    }

    /** 列名归一：数据库大写下划线列名 → 前端契约 camelCase */
    private static Map<String, Object> camelCase(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auditId", row.get("AUDIT_ID"));
        body.put("accountId", row.get("ACCOUNT_ID"));
        body.put("tenantId", row.get("TENANT_ID"));
        body.put("role", row.get("ROLE"));
        body.put("action", row.get("ACTION"));
        body.put("targetType", row.get("TARGET_TYPE"));
        body.put("targetId", row.get("TARGET_ID"));
        body.put("targetVersion", row.get("TARGET_VERSION"));
        body.put("result", row.get("RESULT"));
        body.put("detail", row.get("DETAIL"));
        body.put("createdMs", row.get("CREATED_MS"));
        return body;
    }
}
