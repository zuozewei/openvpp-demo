package com.openvpp.app.controller.operations;

import com.openvpp.common.api.ApiResult;
import com.openvpp.common.context.UnauthorizedAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 运营接口命名空间的异常映射：
 * 1. UnauthorizedAccessException —— 会话缺失/无效/过期、账号口令错误、越角色访问，
 *    统一 401，调用方须重新认证或换用有权限的角色；
 * 2. OperationsResourceNotFoundException —— 对象不在当前数据范围（或未登记），统一 404，
 *    未登记与无权限两种原因响应口径一致，不泄露对象存在性；
 * 3. IllegalStateException —— 业务状态拒绝（截止守门、容量守门、快照失效、非法状态迁移），
 *    统一 409，message 即服务端拒绝原因；
 * 4. IllegalArgumentException —— 入参约束违规（账号/密码为空、申报容量非正等），统一 400。
 * 响应体沿用 ApiResult.error 结构（含 message 字段），前端错误提示按 message 展示。
 */
@RestControllerAdvice(basePackages = "com.openvpp.app.controller.operations")
public class OperationsExceptionAdvice {

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<ApiResult<Void>> unauthorized(UnauthorizedAccessException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResult.error(401, e.getMessage()));
    }

    @ExceptionHandler(OperationsResourceNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> notFound(OperationsResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(404, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResult<Void>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(409, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResult.error(400, e.getMessage()));
    }
}
