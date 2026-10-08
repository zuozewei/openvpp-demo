package com.openvpp.app.auth;

import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.UnauthorizedAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 运营接口登录态拦截器：token → 服务端会话 → IdentityContext。
 *
 * 职责边界（第 52 篇底座）：
 * 1. preHandle：解析 Authorization: Bearer <token>，命中有效会话则把会话身份快照
 *    （账号、租户、角色、场站绑定）写入 IdentityContextHolder；会话缺失/无效/过期
 *    一律抛 UnauthorizedAccessException（映射 401），不静默降级为匿名执行；
 * 2. afterCompletion：finally 语义清理 ThreadLocal——preHandle 放行即必须清理，
 *    防止线程复用串上下文；
 * 3. 租户与角色一律以会话为准：请求体/参数中的 tenantId、role 等字段在任何
 *    下游环节都不得覆盖会话身份（伪造字段由业务层忽略，测试见 OperationsAuthApiTest）。
 *
 * 仅拦截 /api/v1/operations/**（登录入口显式排除），既有 /api/v1/demo 演示入口不受影响。
 */
@Component
public class AuthTokenInterceptor implements HandlerInterceptor {

    public static final String BEARER_PREFIX = "Bearer ";

    private final SessionStore sessionStore;

    public AuthTokenInterceptor(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedAccessException("缺少会话凭证：请求头须携带 Authorization: Bearer <token>");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        SessionEntry entry = sessionStore.resolve(token);
        if (entry == null) {
            throw new UnauthorizedAccessException("会话无效或已过期，请重新登录");
        }
        IdentityContextHolder.set(IdentityContext.of(
                entry.getAccountId(), entry.getTenantId(), entry.getRole(), entry.getStationIds()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        IdentityContextHolder.clear();
    }
}
