package com.openvpp.app.auth;

import com.openvpp.common.context.RoleType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 服务端会话条目：token 与登录态（账号身份快照 + 过期时点）的绑定。
 *
 * 会话条目持有的是账号身份的不可变快照——拦截器每次从会话还原 IdentityContext，
 * 租户/角色/场站始终以本条为准，请求体与参数中的同名字段不参与取值。
 */
public final class SessionEntry {

    private final String token;
    private final String accountId;
    private final String tenantId;
    private final RoleType role;
    private final Set<String> stationIds;
    private final long createdMs;
    private final long expiresAtMs;

    SessionEntry(String token, String accountId, String tenantId, RoleType role,
                 Set<String> stationIds, long createdMs, long expiresAtMs) {
        this.token = Objects.requireNonNull(token, "token 不能为空");
        this.accountId = Objects.requireNonNull(accountId, "accountId 不能为空");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId 不能为空");
        this.role = Objects.requireNonNull(role, "role 不能为空");
        this.stationIds = stationIds == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(stationIds));
        this.createdMs = createdMs;
        this.expiresAtMs = expiresAtMs;
    }

    public String getToken() {
        return token;
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

    public Set<String> getStationIds() {
        return stationIds;
    }

    public long getCreatedMs() {
        return createdMs;
    }

    public long getExpiresAtMs() {
        return expiresAtMs;
    }

    /** 过期判定：到达过期时点即失效（边界取等号，TTL=0 的条目创建即过期） */
    boolean isExpired(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
