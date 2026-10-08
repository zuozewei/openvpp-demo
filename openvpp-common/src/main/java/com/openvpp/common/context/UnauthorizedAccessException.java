package com.openvpp.common.context;

/**
 * 身份上下文缺失或访问未授权 —— 接入工程既有异常约定
 * （RuntimeException 子类 + 中文原因 + 控制器 @ExceptionHandler 按状态码映射）：
 * 请求未在入口建立 IdentityContext 即落到业务代码，或身份越权时被抛出，
 * 控制器统一映射为 401，调用方须重新认证后重试。
 */
public class UnauthorizedAccessException extends RuntimeException {

    public UnauthorizedAccessException(String message) {
        super(message);
    }
}
