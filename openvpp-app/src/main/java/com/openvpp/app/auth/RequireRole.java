package com.openvpp.app.auth;

import com.openvpp.common.context.RoleType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 服务端角色校验注解：标注在运营接口方法上，由 RoleAuthorizationAspect 统一拦截。
 *
 * 安全边界：角色只从服务端会话（IdentityContextHolder）取得，
 * 请求体/参数中任何客户端伪造的角色或租户字段不参与判定；
 * 越角色访问抛 UnauthorizedAccessException（映射 401）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许执行该方法的角色清单；当前角色不在清单内即拒绝 */
    RoleType[] value();
}
