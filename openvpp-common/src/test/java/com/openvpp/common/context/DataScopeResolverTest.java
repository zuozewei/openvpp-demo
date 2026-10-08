package com.openvpp.common.context;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataScopeResolver 规则单测（非全场景覆盖）：
 * 三类业务角色范围解析、无绑定场站返回空范围、SYSTEM_TASK 跨租户须显式标记。
 */
class DataScopeResolverTest {

    @Test
    void 平台管理员解析为租户级范围() {
        IdentityContext context = IdentityContext.of(
                "platform-acc-01", "tenant-openvpp-01", RoleType.PLATFORM_ADMIN,
                Set.of("station-001"));

        DataScope scope = DataScopeResolver.resolve(context);

        assertTrue(scope.isTenantWide(), "平台角色应放开场站维度");
        assertEquals("tenant-openvpp-01", scope.getTenantId(),
                "平台角色数据范围限本 tenantId，不得跨租户");
        assertFalse(scope.isEmpty());
    }

    @Test
    void 运营商解析为绑定场站范围() {
        IdentityContext context = IdentityContext.of(
                "operator-acc-01", "tenant-openvpp-01", RoleType.OPERATOR,
                Set.of("station-001", "station-002"));

        DataScope scope = DataScopeResolver.resolve(context);

        assertFalse(scope.isTenantWide());
        assertEquals(Set.of("station-001", "station-002"), scope.getStationIds());
        assertEquals("tenant-openvpp-01", scope.getTenantId());
        assertFalse(scope.isEmpty());
    }

    @Test
    void 场站运营方解析为绑定场站范围() {
        IdentityContext context = IdentityContext.of(
                "station-acc-01", "tenant-openvpp-01", RoleType.STATION_OPERATOR,
                Set.of("station-009"));

        DataScope scope = DataScopeResolver.resolve(context);

        assertFalse(scope.isTenantWide());
        assertEquals(Set.of("station-009"), scope.getStationIds());
    }

    @Test
    void 无绑定场站返回空范围须短路查询() {
        IdentityContext context = IdentityContext.of(
                "station-acc-02", "tenant-openvpp-01", RoleType.STATION_OPERATOR,
                Set.of());

        DataScope scope = DataScopeResolver.resolve(context);

        // 调用方必须按空范围短路查询，禁止退化为全量查询
        assertTrue(scope.isEmpty());
        assertTrue(scope.getStationIds().isEmpty());
        assertFalse(scope.isTenantWide());
    }

    @Test
    void 系统任务跨租户范围以显式清单为准() {
        // 跨租户服务任务：stationIds 可跨租户，即构造任务上下文时声明的目标范围
        IdentityContext context = IdentityContext.systemTask(
                "settlement-nightly", "tenant-openvpp-01",
                Set.of("station-tenant-a-001", "station-tenant-b-001"));

        DataScope scope = DataScopeResolver.resolve(context);

        assertFalse(scope.isTenantWide());
        assertEquals(Set.of("station-tenant-a-001", "station-tenant-b-001"),
                scope.getStationIds());
        assertFalse(scope.isEmpty());
    }

    @Test
    void 系统任务空清单同样为空范围() {
        IdentityContext context = IdentityContext.systemTask(
                "assessment-archive", "tenant-openvpp-01", Set.of());

        assertTrue(DataScopeResolver.resolve(context).isEmpty());
    }

    @Test
    void 上下文为空直接拒绝() {
        assertThrows(NullPointerException.class, () -> DataScopeResolver.resolve(null));
    }
}
