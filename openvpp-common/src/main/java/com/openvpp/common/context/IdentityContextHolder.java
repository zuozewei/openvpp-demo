package com.openvpp.common.context;

import java.util.Objects;

/**
 * 身份上下文持有者：ThreadLocal 承载当前线程的登录态。
 *
 * 使用约定：
 * 1. 网关/拦截器在请求入口 set(...) 解析结果，出口 finally clear()，
 *    必须在 finally 中清理，防止线程复用串上下文；
 * 2. 业务代码一律用 require() 取上下文 —— 取不到即抛 UnauthorizedAccessException
 *    （映射 401），不允许静默降级为匿名执行；
 * 3. 异步线程（线程池、@Async）默认不带上下文，须由调用方显式传递并按任务语义重建。
 */
public final class IdentityContextHolder {

    private static final ThreadLocal<IdentityContext> HOLDER = new ThreadLocal<>();

    private IdentityContextHolder() {
    }

    public static void set(IdentityContext context) {
        HOLDER.set(Objects.requireNonNull(context, "身份上下文不能为空"));
    }

    /** 可能返回 null；业务代码优先使用 require() */
    public static IdentityContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 取当前线程身份上下文，无上下文时抛未授权异常（接入既有异常体系，映射 401）。
     *
     * @throws UnauthorizedAccessException 当前线程未建立身份上下文
     */
    public static IdentityContext require() {
        IdentityContext context = HOLDER.get();
        if (context == null) {
            throw new UnauthorizedAccessException("当前线程未建立身份上下文，业务操作被拒绝");
        }
        return context;
    }
}
