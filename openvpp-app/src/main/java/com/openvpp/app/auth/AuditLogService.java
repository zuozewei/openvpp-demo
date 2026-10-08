package com.openvpp.app.auth;

import com.openvpp.app.persistence.AuditLogRepository;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 操作审计门面：业务动作经本服务留痕，数据范围查询按身份收窄（仓库层强制）。
 *
 * 两种登记口径：
 * 1. recordForCurrent：已建立身份上下文的动作（后续篇业务操作），身份字段取自
 *    IdentityContextHolder —— 与请求体/参数中的同名字段无任何关系；
 * 2. record：未建立上下文的动作（如登录失败），身份字段由调用方显式传入。
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** 从当前身份上下文留痕（上下文缺失即抛 UnauthorizedAccessException，映射 401） */
    public void recordForCurrent(String action, String targetType, String targetId,
                                 String targetVersion, String result, String detail) {
        IdentityContext context = IdentityContextHolder.require();
        auditLogRepository.insert(context.getAccountId(), context.getTenantId(), context.getRole().name(),
                action, targetType, targetId, targetVersion, result, detail);
    }

    /** 显式身份留痕（登录等身份尚未建立的场景） */
    public void record(String accountId, String tenantId, String role, String action,
                       String targetType, String targetId, String targetVersion,
                       String result, String detail) {
        auditLogRepository.insert(accountId, tenantId, role, action, targetType, targetId,
                targetVersion, result, detail);
    }

    /** 按当前身份查询审计：平台角色本租户全量，其余角色本账号（见 AuditLogRepository） */
    public List<Map<String, Object>> listForCurrentIdentity() {
        return auditLogRepository.listForIdentity(IdentityContextHolder.require());
    }
}
