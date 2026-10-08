package com.openvpp.common.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 身份与租户上下文（不可变值对象）—— 第 52 篇充电桩需求响应运营链路的登录态底座：
 * 网关/拦截器在请求入口解析登录态后写入 IdentityContextHolder，业务代码经 require() 取上下文，
 * 查询条件由 DataScopeResolver 依据本对象解析，任何模块不得绕过上下文自行拼装租户条件。
 *
 * 不变式：
 * 1. stationIds 防御性拷贝 + 不可变包装，构造后外界改动不影响本对象；
 * 2. SYSTEM_TASK 身份只能经 systemTask(...) 显式构造（of(...) 拒绝该角色），
 *    systemTask 标记与角色绑定，杜绝普通登录态伪装跨租户任务。
 */
public final class IdentityContext {

    private final String accountId;
    private final String tenantId;
    private final RoleType role;
    private final Set<String> stationIds;
    private final boolean systemTask;

    private IdentityContext(String accountId, String tenantId, RoleType role,
                            Set<String> stationIds, boolean systemTask) {
        this.accountId = Objects.requireNonNull(accountId, "accountId 不能为空");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId 不能为空");
        this.role = Objects.requireNonNull(role, "role 不能为空");
        // 防御性拷贝并不可变化；null 按"无绑定场站"归一为空集合（解析结果为空范围，而非全量）
        this.stationIds = stationIds == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(stationIds));
        this.systemTask = systemTask;
    }

    /**
     * 构造人类业务身份（平台/运营商/场站）。
     *
     * @throws IllegalArgumentException role 为 SYSTEM_TASK 时抛出 —— 跨租户服务任务
     *                                  必须走 systemTask(...) 显式构造并留审计语义
     */
    public static IdentityContext of(String accountId, String tenantId, RoleType role, Set<String> stationIds) {
        if (role == RoleType.SYSTEM_TASK) {
            throw new IllegalArgumentException(
                    "SYSTEM_TASK 身份必须通过 systemTask(...) 显式构造，并留存审计语义");
        }
        return new IdentityContext(accountId, tenantId, role, stationIds, false);
    }

    /**
     * 显式构造跨租户服务任务身份。accountId 应传任务标识（如 settlement-nightly），
     * 不得传人类账号；调用方必须为每次使用留存审计记录（任务标识、授权依据、目标范围），
     * stationIds 即任务声明的目标范围，空清单表示空范围。
     */
    public static IdentityContext systemTask(String accountId, String tenantId, Set<String> stationIds) {
        return new IdentityContext(accountId, tenantId, RoleType.SYSTEM_TASK, stationIds, true);
    }

    public String getAccountId() {
        return accountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public RoleType getRole() {
        return role;
    }

    /** 可见场站清单（不可变）；无绑定场站时为空集合 */
    public Set<String> getStationIds() {
        return stationIds;
    }

    /** 是否显式构造的跨租户服务任务身份 */
    public boolean isSystemTask() {
        return systemTask;
    }
}
