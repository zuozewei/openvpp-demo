package com.openvpp.app.auth;

import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.IdentityContextHolder;
import com.openvpp.common.context.RoleType;
import com.openvpp.common.context.UnauthorizedAccessException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * @RequireRole 角色校验切面：方法执行前先取服务端会话身份（require 无上下文即 401），
 * 当前角色不在注解清单内即抛 UnauthorizedAccessException（映射 401）。
 *
 * 与拦截器的分工：拦截器负责"登录态是否存在"（token → IdentityContext），
 * 切面负责"该角色能否执行该方法"——两层都在服务端完成，客户端无从绕过。
 */
@Aspect
@Component
public class RoleAuthorizationAspect {

    @Around("@annotation(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        IdentityContext context = IdentityContextHolder.require();
        for (RoleType allowed : requireRole.value()) {
            if (context.getRole() == allowed) {
                return joinPoint.proceed();
            }
        }
        throw new UnauthorizedAccessException(
                "当前角色无权执行该操作，需要 " + Arrays.toString(requireRole.value())
                        + "，实际 " + context.getRole());
    }
}
