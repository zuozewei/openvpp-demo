package com.openvpp.app.controller.operations;

import com.openvpp.common.api.ApiResult;
import com.openvpp.common.context.UnauthorizedAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 运营接口命名空间的异常映射（补齐 UnauthorizedAccessException → 401）：
 * 1. UnauthorizedAccessException —— 会话缺失/无效/过期、账号口令错误、越角色访问，
 *    统一 401，调用方须重新认证或换用有权限的角色；
 * 2. IllegalArgumentException —— 入参约束违规（账号/密码为空等），统一 400。
 * 响应体沿用 ApiResult.error 结构（含 message 字段），前端错误提示按 message 展示。
 */
@RestControllerAdvice(basePackages = "com.openvpp.app.controller.operations")
public class OperationsExceptionAdvice {

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<ApiResult<Void>> unauthorized(UnauthorizedAccessException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResult.error(401, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResult.error(400, e.getMessage()));
    }
}
