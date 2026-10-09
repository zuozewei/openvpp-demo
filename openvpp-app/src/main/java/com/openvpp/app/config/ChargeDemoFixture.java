package com.openvpp.app.config;

import com.openvpp.market.charge.CapabilitySnapshot;
import com.openvpp.market.charge.SnapshotRegistry;
import com.openvpp.resource.org.OperatingEntity;
import com.openvpp.resource.org.OrgRegistryService;
import com.openvpp.resource.org.Station;
import com.openvpp.resource.org.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电桩需求响应演示数据装配（第 53 篇接口层配套，全虚构、不对应任何真实企业）：
 * 让 3 个演示账号开箱即可走通「建事件 → 发布 → 授权 → 快照 → 申报 → 确认」全流程。
 *
 * 种入内容（幂等：已存在即跳过，与 AccountRepository 的种入口径一致）：
 * 1. 业务主体档案：演示租户 tenant-openvpp 下挂 1 个运营主体与 2 个演示场站
 *    cs-station-01 / cs-station-02（账号-场站绑定见 AccountRepository）；
 * 2. 能力快照：两个演示场站各一份评估版本 demo-v1（额定/基线/削峰可信/填谷可信分列），
 *    场站申报时按事件方向取对应可信容量列校验。
 *
 * 注意：本装配直接取系统时间（LocalDateTime.now()）评估快照，不经 Clock Bean——
 * 快照评估是数据准备动作，请求处理时刻才走可替换时钟。
 */
@Component
public class ChargeDemoFixture {

    private static final Logger log = LoggerFactory.getLogger(ChargeDemoFixture.class);

    /** 演示场站一：20 桩 × 120 kW（桩档规模与 openvpp 演示账号绑定一致，数值全虚构） */
    public static final String STATION_ONE = "cs-station-01";
    /** 演示场站二：12 桩 × 60 kW */
    public static final String STATION_TWO = "cs-station-02";
    /** 演示评估版本：平台/场站接口演示与测试共用的初始快照版本 */
    public static final String DEMO_ASSESS_VERSION = "demo-v1";

    private final OrgRegistryService orgRegistryService;
    private final SnapshotRegistry snapshotRegistry;

    public ChargeDemoFixture(OrgRegistryService orgRegistryService, SnapshotRegistry snapshotRegistry) {
        this.orgRegistryService = orgRegistryService;
        this.snapshotRegistry = snapshotRegistry;
    }

    @PostConstruct
    void seedDemoChargeData() {
        seedOrgProfiles();
        seedCapabilitySnapshots();
    }

    private void seedOrgProfiles() {
        tryRegisterTenant();
        tryRegisterEntity();
        tryRegisterStation(STATION_ONE, "openvpp 演示快充站-01");
        tryRegisterStation(STATION_TWO, "openvpp 演示快充站-02");
    }

    private void tryRegisterTenant() {
        try {
            Tenant tenant = new Tenant();
            tenant.setTenantId(DEMO_TENANT);
            tenant.setTenantName("openvpp 演示租户");
            tenant.setServiceRegion("华东/教学区");
            orgRegistryService.registerTenant(tenant);
        } catch (IllegalStateException e) {
            log.info("演示租户已登记，跳过预置: {}", DEMO_TENANT);
        }
    }

    private void tryRegisterEntity() {
        try {
            OperatingEntity entity = new OperatingEntity();
            entity.setEntityId("entity-openvpp-demo");
            entity.setTenantId(DEMO_TENANT);
            entity.setEntityName("openvpp 演示运营主体");
            entity.setEntityKind("充电运营");
            orgRegistryService.registerEntity(entity);
        } catch (IllegalStateException e) {
            log.info("演示运营主体已登记，跳过预置");
        }
    }

    private void tryRegisterStation(String stationId, String stationName) {
        try {
            Station station = new Station();
            station.setStationId(stationId);
            station.setTenantId(DEMO_TENANT);
            station.setEntityId("entity-openvpp-demo");
            station.setStationName(stationName);
            station.setGeoLocation("华东/教学区/演示片区");
            orgRegistryService.registerStation(station);
        } catch (IllegalStateException e) {
            log.info("演示场站已登记，跳过预置: {}", stationId);
        }
    }

    private void seedCapabilitySnapshots() {
        // 评估时间取当前时刻前推 30 分钟：配合默认 480 分钟有效期，演示期内随时可申报
        LocalDateTime assessedAt = LocalDateTime.now().minusMinutes(30);
        tryRegisterSnapshot(STATION_ONE, "ev-cs-station-01", "2400", "900", "600", "800",
                "fp-cs01-sess-a", assessedAt);
        tryRegisterSnapshot(STATION_TWO, "ev-cs-station-02", "720", "300", "180", "240",
                "fp-cs02-sess-a", assessedAt);
    }

    private void tryRegisterSnapshot(String stationId, String resourceId,
                                     String ratedKw, String baselineKw,
                                     String crediblePeakKw, String credibleValleyKw,
                                     String sessionFingerprint, LocalDateTime assessedAt) {
        try {
            snapshotRegistry.register(new CapabilitySnapshot(
                    stationId, resourceId,
                    new BigDecimal(ratedKw), new BigDecimal(baselineKw),
                    new BigDecimal(crediblePeakKw), new BigDecimal(credibleValleyKw),
                    DEMO_ASSESS_VERSION, assessedAt, sessionFingerprint));
        } catch (IllegalStateException e) {
            log.info("演示能力快照已登记，跳过预置: {} / {}", stationId, DEMO_ASSESS_VERSION);
        }
    }

    /** 演示租户标识：与登录账号体系同源（AccountRepository.DEMO_TENANT），避免两处硬编码漂移 */
    private static final String DEMO_TENANT = com.openvpp.app.persistence.AccountRepository.DEMO_TENANT;
}
