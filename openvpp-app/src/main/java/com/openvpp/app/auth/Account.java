package com.openvpp.app.auth;

import com.openvpp.common.context.RoleType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 教学登录账号实体 —— 第 52 篇充电桩需求响应运营链路的登录底座（全部为虚构演示账号）。
 *
 * 设计取舍：
 * 1. 账号-场站绑定以本实体 stationIds 字段自持，不依赖 openvpp-resource 的业务主体模型；
 *    场站名称等展示信息在后续波次与资源侧模型联接；
 * 2. passwordHash 只存加盐散列（格式 salt$hex，见 PasswordDigest），任何环节不落明文；
 * 3. 角色决定数据范围口径（见 openvpp-common 的 DataScopeResolver）：平台角色 stationIds
 *    为空表示租户内全场站（tenantWide），运营商/场站角色为空即空范围、查询必须短路。
 */
public final class Account {

    private final String login;
    private final String passwordHash;
    private final RoleType role;
    private final String tenantId;
    private final Set<String> stationIds;
    private final String displayName;

    public Account(String login, String passwordHash, RoleType role,
                   String tenantId, Set<String> stationIds, String displayName) {
        this.login = Objects.requireNonNull(login, "login 不能为空");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash 不能为空");
        this.role = Objects.requireNonNull(role, "role 不能为空");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId 不能为空");
        this.stationIds = stationIds == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(stationIds));
        this.displayName = displayName == null || displayName.isBlank() ? login : displayName;
    }

    public String getLogin() {
        return login;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public RoleType getRole() {
        return role;
    }

    public String getTenantId() {
        return tenantId;
    }

    /** 绑定场站清单（不可变）；平台角色为空集合（租户内全场站语义） */
    public Set<String> getStationIds() {
        return stationIds;
    }

    public String getDisplayName() {
        return displayName;
    }
}
