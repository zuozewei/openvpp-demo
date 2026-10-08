package com.openvpp.common.context;

import java.util.Objects;

/**
 * 数据范围解析器：把身份上下文翻译成查询过滤条件（DataScope），是租户隔离的执行落点。
 *
 * 解析规则（与身份体系配套，调用方不得自行放宽）：
 * 1. PLATFORM_ADMIN —— 平台角色数据范围限本 tenantId：可见范围为锚定租户内全部场站
 *    （tenantWide 只放开场站维度，租户维度仍锚定本租户，不得跨租户）；
 * 2. OPERATOR / STATION_OPERATOR —— 场站可见范围 = context.stationIds；无绑定场站时
 *    返回空集合，调用方必须按空范围短路查询，禁止退化为全量查询；
 * 3. SYSTEM_TASK —— 跨租户访问的唯一合法身份，且必须由显式构造的服务任务上下文执行：
 *    范围 = 构造任务上下文时声明的 stationIds（可跨租户），空清单即空范围，同样短路。
 */
public final class DataScopeResolver {

    private DataScopeResolver() {
    }

    public static DataScope resolve(IdentityContext context) {
        Objects.requireNonNull(context, "身份上下文不能为空");
        switch (context.getRole()) {
            case PLATFORM_ADMIN:
                // 平台角色数据范围限本 tenantId：tenantWide 仅放开"场站"维度，
                // "租户"维度仍锚定本租户，平台管理员同样不得跨租户取数
                return DataScope.tenantWide(context.getTenantId());
            case OPERATOR:
            case STATION_OPERATOR:
                // 场站可见范围 = stationIds；无绑定场站时为空集合——
                // 调用方必须按空范围短路查询，禁止退化为全量查询
                return DataScope.stations(context.getTenantId(), context.getStationIds());
            case SYSTEM_TASK:
                // 跨租户访问仅 SYSTEM_TASK 且由显式授权的服务任务上下文执行；
                // 范围即任务声明的显式清单（可跨租户），空清单 = 空范围
                return DataScope.stations(context.getTenantId(), context.getStationIds());
            default:
                throw new IllegalStateException("未支持的角色类型: " + context.getRole());
        }
    }
}
