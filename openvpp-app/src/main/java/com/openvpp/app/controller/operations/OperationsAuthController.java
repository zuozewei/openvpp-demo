package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.LoginResult;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.app.auth.RoleCatalog;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营接口认证入口（3.5.3 接口契约第 1 行：登录、当前身份与工作台范围）。
 *
 * 登录：POST /api/v1/operations/auth/login（公开入口，拦截器显式排除）
 *       → 返回 {token, role, roleName, displayName}，字段名与前端会话约定一致；
 * 当前身份：GET /api/v1/operations/auth/me（三类角色均可）
 *       → 返回当前角色、租户、可访问场站集合与允许操作清单，全部取服务端会话。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsAuthController {

    private final AuthService authService;

    public OperationsAuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 教学登录：虚构账号认证，成功返回会话凭证（失败 401、入参缺失 400，见全局异常映射） */
    @PostMapping("/auth/login")
    public LoginResult login(@RequestBody LoginRequest request) {
        return authService.login(request.getAccount(), request.getPassword());
    }

    /**
     * 当前身份与工作台范围：角色/租户/场站/允许操作一律取服务端会话（IdentityContext）。
     *
     * 安全边界演示：tenantId 参数即使传入也只被忽略——客户端伪造的租户字段
     * 不影响数据范围，响应中的 tenantId 恒为会话租户（测试见 OperationsAuthApiTest）。
     */
    @RequireRole({RoleType.PLATFORM_ADMIN, RoleType.OPERATOR, RoleType.STATION_OPERATOR})
    @GetMapping("/auth/me")
    public Map<String, Object> me(@RequestParam(required = false) String tenantId) {
        IdentityContext context = IdentityContextHolder.require();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", context.getAccountId());
        body.put("role", RoleCatalog.codeOf(context.getRole()));
        body.put("roleName", RoleCatalog.roleNameOf(context.getRole()));
        body.put("tenantId", context.getTenantId());
        body.put("stationIds", new ArrayList<>(context.getStationIds()));
        body.put("allowedOperations", RoleCatalog.allowedOperationsOf(context.getRole()));
        return body;
    }

    /** 登录请求体：字段名与前端脚手架提交结构一致（account / password） */
    public static final class LoginRequest {
        private String account;
        private String password;

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
