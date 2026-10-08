package com.openvpp.app.auth;

import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.common.context.UnauthorizedAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录态拦截器单测：token 还原 IdentityContext 写入 Holder、出口清理 ThreadLocal、
 * 会话缺失/无效/过期统一 401（UnauthorizedAccessException）。
 */
class AuthTokenInterceptorTest {

    private static final Account STATION_ACCOUNT = new Account(
            "station_admin", "hash-unused", RoleType.STATION_OPERATOR,
            "tenant-openvpp", Set.of("cs-station-01"), "场站-演示账号");

    private final SessionStore sessionStore = new SessionStore(30);
    private final AuthTokenInterceptor interceptor = new AuthTokenInterceptor(sessionStore);

    @AfterEach
    void tearDown() {
        // ThreadLocal 兜底清理：拦截器自身负责 clear，这里防止失败用例泄漏到后续用例
        IdentityContextHolder.clear();
    }

    @Test
    void preHandleRestoresContextFromBearerToken() {
        SessionEntry entry = sessionStore.create(STATION_ACCOUNT);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION,
                AuthTokenInterceptor.BEARER_PREFIX + entry.getToken());

        boolean passed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        assertTrue(passed);
        assertEquals("station_admin", IdentityContextHolder.require().getAccountId());
        assertEquals("tenant-openvpp", IdentityContextHolder.require().getTenantId());
        assertEquals(RoleType.STATION_OPERATOR, IdentityContextHolder.require().getRole());
        assertEquals(Set.of("cs-station-01"), IdentityContextHolder.require().getStationIds());
    }

    @Test
    void afterCompletionClearsThreadLocal() {
        SessionEntry entry = sessionStore.create(STATION_ACCOUNT);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION,
                AuthTokenInterceptor.BEARER_PREFIX + entry.getToken());
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertNull(IdentityContextHolder.get(), "出口必须清理 ThreadLocal，防线程复用串上下文");
    }

    @Test
    void missingAuthorizationHeaderRejected() {
        UnauthorizedAccessException ex = assertThrows(UnauthorizedAccessException.class,
                () -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
        assertTrue(ex.getMessage().contains("缺少会话凭证"));
    }

    @Test
    void nonBearerHeaderRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic c3RhdGlvbjpwYXNz");
        assertThrows(UnauthorizedAccessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void unknownOrExpiredTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION,
                AuthTokenInterceptor.BEARER_PREFIX + "unknown-token");
        UnauthorizedAccessException ex = assertThrows(UnauthorizedAccessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertTrue(ex.getMessage().contains("会话无效或已过期"));

        // TTL=0 的会话创建即过期，resolve 命中失败同样 401
        SessionStore zeroTtlStore = new SessionStore(0);
        SessionEntry expired = zeroTtlStore.create(STATION_ACCOUNT);
        Thread.sleep(2);
        AuthTokenInterceptor zeroTtlInterceptor = new AuthTokenInterceptor(zeroTtlStore);
        MockHttpServletRequest expiredRequest = new MockHttpServletRequest();
        expiredRequest.addHeader(HttpHeaders.AUTHORIZATION,
                AuthTokenInterceptor.BEARER_PREFIX + expired.getToken());
        assertThrows(UnauthorizedAccessException.class,
                () -> zeroTtlInterceptor.preHandle(expiredRequest, new MockHttpServletResponse(), new Object()));
    }
}
