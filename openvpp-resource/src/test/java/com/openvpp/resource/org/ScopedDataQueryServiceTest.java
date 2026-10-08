package com.openvpp.resource.org;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.common.enums.ResourceType;
import com.openvpp.resource.ledger.AuditTrail;
import com.openvpp.resource.ledger.ResourceLedgerService;
import com.openvpp.resource.profile.EvChargerResourceProfile;
import com.openvpp.resource.profile.ResourceProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据范围查询单测（第 52 篇底座执行落点）：
 * 平台限本租户 / 运营商与场站角色限绑定清单 / 同租户跨主体互不可见 /
 * 跨租户不可见 / 空范围短路 / 归属链解析 / 未挂靠资源不参与范围查询。
 *
 * 范围构造一律经 DataScopeResolver.resolve(IdentityContext) ——
 * DataScope 的唯一合法来源，与线上执行路径一致。
 */
class ScopedDataQueryServiceTest {

    private OrgRegistryService registry;
    private ResourceLedgerService ledger;
    private ScopedDataQueryService queryService;
    private DemoOrgFixture fixture;

    @BeforeEach
    void setUp() {
        registry = new OrgRegistryService();
        ledger = new ResourceLedgerService(new AuditTrail());
        fixture = DemoOrgDataInitializer.initialize(registry, ledger);
        queryService = new ScopedDataQueryService(registry, ledger);
    }

    private static DataScope scopeOf(IdentityContext context) {
        return DataScopeResolver.resolve(context);
    }

    private static Set<String> stationIdsOf(List<Station> stations) {
        return stations.stream().map(Station::getStationId).collect(Collectors.toSet());
    }

    private static Set<String> resourceIdsOf(List<ResourceProfile> resources) {
        return resources.stream().map(ResourceProfile::getResourceId).collect(Collectors.toSet());
    }

    @Test
    void 平台角色数据范围限本租户() {
        IdentityContext platform = IdentityContext.of(
                "platform-acc-01", "T-1001", RoleType.PLATFORM_ADMIN, Set.of());
        DataScope scope = scopeOf(platform);

        List<Station> stations = queryService.queryStations(scope);
        assertEquals(Set.of("S-11101", "S-11102", "S-11201"), stationIdsOf(stations),
                "平台角色应看到本租户全部场站");
        assertFalse(stationIdsOf(stations).contains("S-21101"), "跨租户场站不得可见");

        List<ResourceProfile> resources = queryService.queryResources(scope, ResourceType.FL);
        assertEquals(Set.of("ev-s11101", "ev-s11102", "ev-s11201"), resourceIdsOf(resources),
                "平台角色应看到本租户场站下挂的全部充电桩资源");
    }

    @Test
    void 平台角色跨租户不可见() {
        IdentityContext platformT2 = IdentityContext.of(
                "platform-acc-02", "T-2001", RoleType.PLATFORM_ADMIN, Set.of());

        List<Station> stations = queryService.queryStations(scopeOf(platformT2));
        assertEquals(Set.of("S-21101"), stationIdsOf(stations), "T-2001 平台只可见本租户场站");
    }

    @Test
    void 运营商角色限绑定场站清单() {
        IdentityContext operator = IdentityContext.of(
                "operator-acc-01", "T-1001", RoleType.OPERATOR, Set.of("S-11101", "S-11201"));

        assertEquals(Set.of("S-11101", "S-11201"),
                stationIdsOf(queryService.queryStations(scopeOf(operator))));
        assertEquals(Set.of("ev-s11101", "ev-s11201"),
                resourceIdsOf(queryService.queryResources(scopeOf(operator))),
                "资源可见性应跟随场站清单");
    }

    @Test
    void 同租户跨主体场站互不可见() {
        // E-1101 的运营商绑定本主体两场站；S-11201 同属 T-1001 但归属 E-1102 —— 不可见
        IdentityContext operatorE1101 = IdentityContext.of(
                "operator-acc-e1101", "T-1001", RoleType.OPERATOR, Set.of("S-11101", "S-11102"));

        List<Station> stations = queryService.queryStations(scopeOf(operatorE1101));
        assertEquals(Set.of("S-11101", "S-11102"), stationIdsOf(stations));
        assertFalse(stationIdsOf(stations).contains("S-11201"),
                "同租户其他主体的场站必须互不可见");

        List<ResourceProfile> resources = queryService.queryResources(scopeOf(operatorE1101));
        assertFalse(resourceIdsOf(resources).contains("ev-s11201"));
    }

    @Test
    void 场站运营方限本场站() {
        IdentityContext stationOp = IdentityContext.of(
                "station-acc-01", "T-1001", RoleType.STATION_OPERATOR, Set.of("S-11102"));

        assertEquals(Set.of("S-11102"), stationIdsOf(queryService.queryStations(scopeOf(stationOp))));
        assertEquals(Set.of("ev-s11102"),
                resourceIdsOf(queryService.queryResources(scopeOf(stationOp))));
    }

    @Test
    void 无绑定场站空范围短路不退化为全量() {
        IdentityContext noBinding = IdentityContext.of(
                "station-acc-none", "T-1001", RoleType.STATION_OPERATOR, Set.of());
        DataScope scope = scopeOf(noBinding);
        assertTrue(scope.isEmpty(), "无绑定场站应解析为空范围");

        // 台账与登记簿里实际有数据 —— 空范围短路后必须返回空，而不是全量
        assertTrue(queryService.queryStations(scope).isEmpty(), "空范围查询场站必须短路为空结果");
        assertTrue(queryService.queryResources(scope).isEmpty(), "空范围查询资源必须短路为空结果");
        assertTrue(queryService.queryResources(scope, ResourceType.FL).isEmpty());
        assertEquals(4, registry.listStations("T-1001").size() + registry.listStations("T-2001").size(),
                "前置条件：登记簿确有场站，证明短路而非全量");
    }

    @Test
    void 跨租户场站在绑定清单外不可见() {
        // T-2001 的运营商按绑定只持本场站；T-1001 的场站与资源全部不可见
        IdentityContext operatorT2 = IdentityContext.of(
                "operator-acc-t2", "T-2001", RoleType.OPERATOR, Set.of("S-21101"));

        assertEquals(Set.of("S-21101"), stationIdsOf(queryService.queryStations(scopeOf(operatorT2))));
        assertEquals(Set.of("ev-s21101"),
                resourceIdsOf(queryService.queryResources(scopeOf(operatorT2), ResourceType.FL)));
    }

    @Test
    void 系统任务跨租户显式清单可查() {
        // SYSTEM_TASK 是跨租户访问的唯一合法身份：清单可跨租户，以显式声明为准
        IdentityContext task = IdentityContext.systemTask(
                "settlement-nightly", "T-1001", Set.of("S-11101", "S-21101"));

        assertEquals(Set.of("S-11101", "S-21101"),
                stationIdsOf(queryService.queryStations(scopeOf(task))));
    }

    @Test
    void 归属链四级解析正确() {
        OwnershipChain chain = queryService.chainOf("ev-s11101");
        assertEquals("ev-s11101", chain.getResourceId());
        assertEquals("S-11101", chain.getStationId());
        assertEquals("E-1101", chain.getEntityId());
        assertEquals("T-1001", chain.getTenantId());

        OwnershipChain chainT2 = queryService.chainOf("ev-s21101");
        assertEquals("S-21101", chainT2.getStationId());
        assertEquals("E-2101", chainT2.getEntityId());
        assertEquals("T-2001", chainT2.getTenantId());
    }

    @Test
    void 未挂靠场站资源不参与范围查询且归属链拒绝解析() {
        EvChargerResourceProfile orphan = new EvChargerResourceProfile();
        orphan.setResourceId("ev-orphan");
        orphan.setCapacity(new BigDecimal("240"));
        orphan.setPileCount(2);
        orphan.setPileRateKw(new BigDecimal("120"));
        orphan.setGeoLocation("华东/临州/未知片区");
        orphan.setElectricalNode("10kV/馈线X9");
        orphan.setContractEnd(LocalDate.now().plusYears(1));
        // 故意不设置 stationId —— 未挂靠场站
        ledger.enroll(orphan, "acct-ev-orphan");

        IdentityContext platform = IdentityContext.of(
                "platform-acc-01", "T-1001", RoleType.PLATFORM_ADMIN, Set.of());
        List<ResourceProfile> resources = queryService.queryResources(scopeOf(platform));
        assertFalse(resourceIdsOf(resources).contains("ev-orphan"),
                "未挂靠场站的资源在按范围查询中不可见");

        assertThrows(IllegalStateException.class, () -> queryService.chainOf("ev-orphan"),
                "缺少场站归属键的资源必须拒绝解析归属链");
    }

    @Test
    void 示例数据规模符合演示口径() {
        assertEquals(2, fixture.getTenantIds().size());
        assertEquals(3, fixture.getEntityIds().size(), "每租户 1~2 个运营主体");
        assertEquals(4, fixture.getStationIds().size(), "每主体 1~2 个场站");
        assertEquals(4, fixture.getResourceIds().size(), "每个场站下挂 1 个充电桩资源");
    }
}
