package com.openvpp.app.config;

import com.openvpp.market.charge.CapacityOccupancyLedger;
import com.openvpp.market.charge.ChargeDeclarationService;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.SnapshotRegistry;
import com.openvpp.resource.org.OrgRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * 充电桩需求响应运营装配（第 53 篇接口层底座）：事件服务 / 申报服务 / 快照登记簿 /
 * 容量预占台账 / 业务主体登记簿的 Bean 装配。
 *
 * 设计取舍：
 * 1. openvpp-market 与 openvpp-resource 的上述服务均为纯 POJO（无 Spring 注解），
 *    由启动模块统一装配，对象图与 market 模块单测一致；
 * 2. 快照有效期（ttl）走配置 openvpp.charge.snapshot-ttl-minutes，教学默认 480 分钟；
 * 3. 运营时间一律经 Clock Bean 取（LocalDateTime.now(clock)），测试可替换时钟
 *    验证"截止守门含时刻本身"等时间边界，业务口径不变。
 */
@Configuration
public class ChargeDrOperationsConfig {

    /** 运营接口时钟：默认系统时区；测试以 MockBean 替换以推进时间 */
    @Bean
    public Clock operationsClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public OrgRegistryService orgRegistryService() {
        return new OrgRegistryService();
    }

    @Bean
    public ChargeDrEventService chargeDrEventService() {
        return new ChargeDrEventService();
    }

    @Bean
    public SnapshotRegistry snapshotRegistry(
            @Value("${openvpp.charge.snapshot-ttl-minutes:480}") long ttlMinutes) {
        return new SnapshotRegistry(Duration.ofMinutes(ttlMinutes));
    }

    @Bean
    public CapacityOccupancyLedger capacityOccupancyLedger() {
        return new CapacityOccupancyLedger();
    }

    @Bean
    public ChargeDeclarationService chargeDeclarationService(ChargeDrEventService eventService,
                                                             SnapshotRegistry snapshotRegistry,
                                                             CapacityOccupancyLedger occupancyLedger) {
        return new ChargeDeclarationService(eventService, snapshotRegistry, occupancyLedger);
    }
}
