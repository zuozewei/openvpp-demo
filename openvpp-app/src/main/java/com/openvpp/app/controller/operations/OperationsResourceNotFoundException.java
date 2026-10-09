package com.openvpp.app.controller.operations;

/**
 * 运营接口"对象不可见/不存在"异常 —— 数据范围外的对象一律按不可见处理（映射 404），
 * 不向调用方泄露对象是否真实存在（避免按响应差异探测他方数据）。
 *
 * 触发场景：按 DataScope 过滤后的详情查询未命中 —— 无论"未登记"还是"不在当前数据范围内"，
 * 响应口径一致（404 + 同一文案），调用方无从区分两种原因。
 */
public class OperationsResourceNotFoundException extends RuntimeException {

    public OperationsResourceNotFoundException(String message) {
        super(message);
    }
}
