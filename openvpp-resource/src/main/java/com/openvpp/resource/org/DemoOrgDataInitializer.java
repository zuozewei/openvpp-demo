package com.openvpp.resource.org;

import com.openvpp.resource.ledger.ResourceLedgerService;
import com.openvpp.resource.profile.EvChargerResourceProfile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务主体示例数据装配器 —— 第 52 篇底座演示数据（全虚构，不对应任何真实企业）。
 *
 * 规模口径：2 个租户 × 每租户 1~2 个运营主体 × 每主体 1~2 个场站，
 * 每个场站下挂 1 个站级充电桩资源档案（桩数/单桩功率/V2G/车辆构成）。
 * 归属链全部经 OrgRegistryService 校验登记，资源建档走 ResourceLedgerService 全量校验。
 *
 * 调用方（如应用侧演示入口）在启动时调用一次 initialize(...) 即可：
 * 演示数据的身份绑定（登录账号 ↔ stationIds）由应用侧账号体系完成，本装配器不含账号概念。
 */
public final class DemoOrgDataInitializer {

    private DemoOrgDataInitializer() {
    }

    /** 装配虚构示例数据，返回 ID 清单快照 */
    public static DemoOrgFixture initialize(OrgRegistryService registry, ResourceLedgerService ledger) {
        List<String> tenantIds = new ArrayList<>();
        List<String> entityIds = new ArrayList<>();
        List<String> stationIds = new ArrayList<>();
        List<String> resourceIds = new ArrayList<>();

        // —— 租户一：云澜能源（教学虚构） ——
        registry.registerTenant(tenant("T-1001", "云澜能源", "华东/临州"));
        tenantIds.add("T-1001");

        registry.registerEntity(entity("E-1101", "T-1001", "云澜车联运营", "充电运营"));
        registry.registerEntity(entity("E-1102", "T-1001", "云澜车网互动", "车网互动"));
        entityIds.add("E-1101");
        entityIds.add("E-1102");

        registry.registerStation(station("S-11101", "T-1001", "E-1101", "云澜·城东快充站", "华东/临州/城东片区"));
        registry.registerStation(station("S-11102", "T-1001", "E-1101", "云澜·高新园区站", "华东/临州/高新园区"));
        registry.registerStation(station("S-11201", "T-1001", "E-1102", "云澜·枢纽V2G示范站", "华东/临州/高铁枢纽"));
        stationIds.add("S-11101");
        stationIds.add("S-11102");
        stationIds.add("S-11201");

        // —— 租户二：星驰绿能（教学虚构） ——
        registry.registerTenant(tenant("T-2001", "星驰绿能", "华南/澜江"));
        tenantIds.add("T-2001");

        registry.registerEntity(entity("E-2101", "T-2001", "星驰充电运营", "充电运营"));
        entityIds.add("E-2101");

        registry.registerStation(station("S-21101", "T-2001", "E-2101", "星驰·机场快充站", "华南/澜江/临空片区"));
        stationIds.add("S-21101");

        // —— 场站下挂站级充电桩资源档案（台账校验全量生效） ——
        enrollCharger(ledger, resourceIds,
                "ev-s11101", "S-11101", "acct-ev-s11101",
                "华东/临州/城东片区", "10kV/馈线F1",
                20, "120", false, "网约40/私家35/物流15/出租10");
        enrollCharger(ledger, resourceIds,
                "ev-s11102", "S-11102", "acct-ev-s11102",
                "华东/临州/高新园区", "10kV/馈线F2",
                12, "60", false, "私家55/网约30/物流15");
        enrollCharger(ledger, resourceIds,
                "ev-s11201", "S-11201", "acct-ev-s11201",
                "华东/临州/高铁枢纽", "10kV/馈线F3",
                8, "120", true, "网约60/私家40");
        enrollCharger(ledger, resourceIds,
                "ev-s21101", "S-21101", "acct-ev-s21101",
                "华南/澜江/临空片区", "10kV/馈线G1",
                16, "180", false, "网约45/私家25/物流20/出租10");

        return new DemoOrgFixture(tenantIds, entityIds, stationIds, resourceIds);
    }

    private static Tenant tenant(String tenantId, String name, String region) {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setTenantName(name);
        t.setServiceRegion(region);
        return t;
    }

    private static OperatingEntity entity(String entityId, String tenantId, String name, String kind) {
        OperatingEntity e = new OperatingEntity();
        e.setEntityId(entityId);
        e.setTenantId(tenantId);
        e.setEntityName(name);
        e.setEntityKind(kind);
        return e;
    }

    private static Station station(String stationId, String tenantId, String entityId, String name, String geo) {
        Station s = new Station();
        s.setStationId(stationId);
        s.setTenantId(tenantId);
        s.setEntityId(entityId);
        s.setStationName(name);
        s.setGeoLocation(geo);
        return s;
    }

    private static void enrollCharger(ResourceLedgerService ledger, List<String> resourceIds,
                                      String resourceId, String stationId, String gridAccount,
                                      String geo, String node,
                                      int pileCount, String pileRateKw, boolean v2g, String vehicleMix) {
        EvChargerResourceProfile p = new EvChargerResourceProfile();
        p.setResourceId(resourceId);
        p.setStationId(stationId);
        p.setPileCount(pileCount);
        p.setPileRateKw(new BigDecimal(pileRateKw));
        p.setCapacity(new BigDecimal(pileRateKw).multiply(BigDecimal.valueOf(pileCount)));
        p.setV2gCapable(v2g);
        p.setVehicleMix(vehicleMix);
        p.setGeoLocation(geo);
        p.setElectricalNode(node);
        p.setContractEnd(LocalDate.now().plusYears(1));
        ledger.enroll(p, gridAccount);
        resourceIds.add(resourceId);
    }
}
