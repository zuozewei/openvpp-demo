package com.openvpp.common.context;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdentityContext 不变式单测：构造取值 / 防御性拷贝与不可变包装 /
 * SYSTEM_TASK 须显式构造（of(...) 拒绝该角色）。
 */
class IdentityContextTest {

    @Test
    void 普通身份构造与取值() {
        IdentityContext context = IdentityContext.of(
                "operator-acc-01", "tenant-openvpp-01", RoleType.OPERATOR,
                Set.of("station-001", "station-002"));

        assertEquals("operator-acc-01", context.getAccountId());
        assertEquals("tenant-openvpp-01", context.getTenantId());
        assertEquals(RoleType.OPERATOR, context.getRole());
        assertEquals(Set.of("station-001", "station-002"), context.getStationIds());
        assertFalse(context.isSystemTask());
    }

    @Test
    void 场站清单防御性拷贝且不可变() {
        Set<String> source = new HashSet<>(Set.of("station-001"));
        IdentityContext context = IdentityContext.of(
                "station-acc-01", "tenant-openvpp-01", RoleType.STATION_OPERATOR, source);

        // 构造后改动外部集合不影响上下文内部状态
        source.add("station-evil");
        assertEquals(Set.of("station-001"), context.getStationIds());

        // 对外暴露的集合不可修改
        assertThrows(UnsupportedOperationException.class,
                () -> context.getStationIds().add("station-evil"));
    }

    @Test
    void 空绑定场站归一为空集合而非全量() {
        IdentityContext context = IdentityContext.of(
                "station-acc-02", "tenant-openvpp-01", RoleType.STATION_OPERATOR, null);

        assertTrue(context.getStationIds().isEmpty());
    }

    @Test
    void 系统任务角色走普通构造被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IdentityContext.of("human-acc", "tenant-openvpp-01",
                        RoleType.SYSTEM_TASK, Set.of("station-001")));
        assertTrue(e.getMessage().contains("systemTask"));
    }

    @Test
    void 系统任务身份显式构造并带标记() {
        IdentityContext context = IdentityContext.systemTask(
                "settlement-nightly", "tenant-openvpp-01",
                Set.of("station-tenant-a-001", "station-tenant-b-001"));

        assertEquals(RoleType.SYSTEM_TASK, context.getRole());
        assertTrue(context.isSystemTask());
        assertEquals(Set.of("station-tenant-a-001", "station-tenant-b-001"),
                context.getStationIds(), "跨租户清单应原样保留为任务声明的目标范围");
    }
}
