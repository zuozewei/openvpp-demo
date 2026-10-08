package com.openvpp.resource.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务主体登记全场景单测：
 * 三级登记 / 逐级归属校验 / 跨租户挂靠拦截 / 重复登记拦截 / 归属链取数。
 */
class OrgRegistryServiceTest {

    private OrgRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = new OrgRegistryService();
    }

    private static Tenant tenant(String tenantId) {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setTenantName("租户-" + tenantId);
        t.setServiceRegion("华东/临州");
        return t;
    }

    private static OperatingEntity entity(String entityId, String tenantId) {
        OperatingEntity e = new OperatingEntity();
        e.setEntityId(entityId);
        e.setTenantId(tenantId);
        e.setEntityName("主体-" + entityId);
        e.setEntityKind("充电运营");
        return e;
    }

    private static Station station(String stationId, String tenantId, String entityId) {
        Station s = new Station();
        s.setStationId(stationId);
        s.setTenantId(tenantId);
        s.setEntityId(entityId);
        s.setStationName("场站-" + stationId);
        s.setGeoLocation("华东/临州/片区");
        return s;
    }

    @Test
    void 三级登记形成完整归属链() {
        registry.registerTenant(tenant("T-1"));
        registry.registerEntity(entity("E-1", "T-1"));
        registry.registerStation(station("S-1", "T-1", "E-1"));
        registry.registerStation(station("S-2", "T-1", "E-1"));

        assertEquals(1, registry.listEntities("T-1").size());
        assertEquals(2, registry.listStations("T-1").size());
        assertEquals(2, registry.listStationsByEntity("E-1").size());
        // 按 ID 排序，演示输出稳定
        assertEquals("S-1", registry.listStations("T-1").get(0).getStationId());
    }

    @Test
    void 租户重复登记拒绝() {
        registry.registerTenant(tenant("T-1"));
        assertThrows(IllegalStateException.class, () -> registry.registerTenant(tenant("T-1")));
    }

    @Test
    void 主体挂靠未登记租户拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerEntity(entity("E-1", "T-不存在")));
    }

    @Test
    void 主体重复登记拒绝() {
        registry.registerTenant(tenant("T-1"));
        registry.registerEntity(entity("E-1", "T-1"));
        assertThrows(IllegalStateException.class, () -> registry.registerEntity(entity("E-1", "T-1")));
    }

    @Test
    void 场站挂靠未登记主体拒绝() {
        registry.registerTenant(tenant("T-1"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerStation(station("S-1", "T-1", "E-不存在")));
    }

    @Test
    void 场站跨租户挂靠拒绝() {
        registry.registerTenant(tenant("T-1"));
        registry.registerTenant(tenant("T-2"));
        registry.registerEntity(entity("E-1", "T-1"));

        // 主体归属 T-1，场站把 tenantId 写成 T-2 —— 归属链断裂必须拦截
        assertThrows(IllegalStateException.class,
                () -> registry.registerStation(station("S-1", "T-2", "E-1")),
                "场站 tenantId 与主体归属租户不一致时必须拒绝");
    }

    @Test
    void 场站重复登记拒绝() {
        registry.registerTenant(tenant("T-1"));
        registry.registerEntity(entity("E-1", "T-1"));
        registry.registerStation(station("S-1", "T-1", "E-1"));
        assertThrows(IllegalStateException.class,
                () -> registry.registerStation(station("S-1", "T-1", "E-1")));
    }

    @Test
    void 显式清单查询以登记在册为限() {
        registry.registerTenant(tenant("T-1"));
        registry.registerEntity(entity("E-1", "T-1"));
        registry.registerStation(station("S-1", "T-1", "E-1"));
        registry.registerStation(station("S-2", "T-1", "E-1"));

        List<Station> hit = registry.listStations(Set.of("S-2", "S-未登记"));
        assertEquals(1, hit.size());
        assertEquals("S-2", hit.get(0).getStationId());

        assertTrue(registry.listStations(Set.of()).isEmpty());
        assertTrue(registry.listStations((Set<String>) null).isEmpty());
    }

    @Test
    void 租户锚定清单过滤拦截跨租户项() {
        registry.registerTenant(tenant("T-1"));
        registry.registerTenant(tenant("T-2"));
        registry.registerEntity(entity("E-1", "T-1"));
        registry.registerEntity(entity("E-2", "T-2"));
        registry.registerStation(station("S-1", "T-1", "E-1"));
        registry.registerStation(station("S-2", "T-2", "E-2"));

        List<Station> anchored = registry.listStations("T-1", Set.of("S-1", "S-2"));
        assertEquals(1, anchored.size(), "T-1 锚定下 T-2 场站必须被拦截");
        assertEquals("S-1", anchored.get(0).getStationId());
    }
}
