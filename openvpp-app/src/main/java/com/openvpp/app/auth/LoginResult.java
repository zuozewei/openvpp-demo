package com.openvpp.app.auth;

/**
 * 登录成功返回体 —— 字段名与 openvpp-admin 前端会话约定严格一致
 * （token / role / roleName / displayName，role 取 platform/operator/station 短码），
 * 前端登录成功后整体存入会话存储并携带 token 访问后续接口。
 */
public final class LoginResult {

    private final String token;
    private final String role;
    private final String roleName;
    private final String displayName;

    public LoginResult(String token, String role, String roleName, String displayName) {
        this.token = token;
        this.role = role;
        this.roleName = roleName;
        this.displayName = displayName;
    }

    public String getToken() {
        return token;
    }

    public String getRole() {
        return role;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
