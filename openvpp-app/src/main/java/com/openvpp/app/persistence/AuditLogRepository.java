package com.openvpp.app.persistence;

import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 操作审计仓库 —— JdbcTemplate + H2 文件库（沿用 app 持久化方式）。
 *
 * 查询口径（数据范围由仓库层强制，调用方不得自行放宽）：
 * 1. 任何查询恒带租户条件 tenant_id（审计行写入时即登记租户）；
 * 2. listForIdentity 按身份收窄：平台角色可见本租户全部审计；其余角色仅限本账号记录。
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 审计留痕：账号、角色、动作、业务对象、版本、时间、结果一次写入。
     * targetVersion 在尚无版本概念的动作（如 LOGIN）上登记 '-'。
     */
    public void insert(String accountId, String tenantId, String role, String action,
                       String targetType, String targetId, String targetVersion,
                       String result, String detail) {
        jdbc.update("INSERT INTO operation_audit "
                        + "(account_id,tenant_id,role,action,target_type,target_id,target_version,result,detail,created_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                accountId, tenantId, role, action, targetType, targetId, targetVersion, result, detail,
                System.currentTimeMillis());
    }

    /**
     * 按身份查询：平台角色 = 本租户全量审计；其余角色 = 本租户内本账号记录。
     * 其余角色即使知晓同租户其他账号的账号名，也无法经本方法越权读取其审计。
     */
    public List<Map<String, Object>> listForIdentity(IdentityContext context) {
        if (context.getRole() == RoleType.PLATFORM_ADMIN) {
            return jdbc.queryForList(
                    "SELECT * FROM operation_audit WHERE tenant_id=? ORDER BY created_ms DESC, audit_id DESC",
                    context.getTenantId());
        }
        return listForAccount(context.getTenantId(), context.getAccountId());
    }

    /** 按账号查询（恒带租户条件）：返回该账号在本租户内的全部审计记录 */
    public List<Map<String, Object>> listForAccount(String tenantId, String accountId) {
        return jdbc.queryForList(
                "SELECT * FROM operation_audit WHERE tenant_id=? AND account_id=? "
                        + "ORDER BY created_ms DESC, audit_id DESC",
                tenantId, accountId);
    }

    /** 某账号在指定动作上的留痕条数（测试断言用） */
    public int countByAction(String tenantId, String accountId, String action) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_audit WHERE tenant_id=? AND account_id=? AND action=?",
                Integer.class, tenantId, accountId, action);
        return n == null ? 0 : n;
    }
}
