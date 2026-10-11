package com.openvpp.app.config;

import com.openvpp.aggregator.plan.AggregatePlanRepository;
import com.openvpp.aggregator.plan.AggregatePlanService;
import com.openvpp.aggregator.plan.ConstraintAllocator;
import com.openvpp.dispatch.command.ChargePileCommandService;
import com.openvpp.dispatch.command.DispatchCommandRepository;
import com.openvpp.market.charge.ChargeDeclarationService;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.SnapshotRegistry;
import com.openvpp.app.service.ChargePileDeviceYard;
import com.openvpp.app.service.PlanDispatchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 分级派单装配（第 54 篇接口层底座）：分级计划服务 / 单桩指令服务 / 模拟设备院子
 * 的 Bean 装配，把 aggregator.plan 与 dispatch.command 的库形态能力接入 Spring 容器。
 *
 * 设计取舍（与 ChargeDrOperationsConfig、MonitoringConfig 同口径）：
 * 1. aggregator / dispatch / iot 的上述服务均为纯 POJO（无 Spring 注解），
 *    由启动模块统一装配，对象图与各模块单测一致；
 * 2. 下行通道装配为 {@link ChargePileDeviceYard}（本地模拟设备院子，一资源一实例），
 *    既有 InstructionService 的日志型下行回调保持不动 —— 两条链路并存，
 *    真实设备接入时以"协议适配实现 ChargePileDownlinkChannel"替换院子，调用方零改动；
 * 3. 运营时间一律经 operationsClock Bean 取（见 {@link ChargeDrOperationsConfig}），
 *    测试可替换时钟验证"申报截止即派单"等时间边界，业务口径不变。
 */
@Configuration
public class PlanDispatchConfig {

    @Bean
    public AggregatePlanRepository aggregatePlanRepository() {
        return new AggregatePlanRepository();
    }

    @Bean
    public ConstraintAllocator constraintAllocator() {
        return new ConstraintAllocator();
    }

    @Bean
    public AggregatePlanService aggregatePlanService(ChargeDrEventService eventService,
                                                     SnapshotRegistry snapshotRegistry,
                                                     ConstraintAllocator constraintAllocator,
                                                     AggregatePlanRepository aggregatePlanRepository) {
        return new AggregatePlanService(eventService, snapshotRegistry,
                constraintAllocator, aggregatePlanRepository);
    }

    @Bean
    public ChargePileDeviceYard chargePileDeviceYard() {
        return new ChargePileDeviceYard();
    }

    @Bean
    public ChargePileCommandService chargePileCommandService(
            DispatchCommandRepository dispatchCommandRepository,
            ChargePileDeviceYard chargePileDeviceYard) {
        // 院子实现 ChargePileDownlinkChannel 契约：指令服务只面向契约编程，
        // 真实设备接入时以协议适配实现替换院子，调用方零改动
        return new ChargePileCommandService(dispatchCommandRepository, chargePileDeviceYard);
    }

    @Bean
    public PlanDispatchService planDispatchService(AggregatePlanService aggregatePlanService,
                                                   ChargePileCommandService chargePileCommandService,
                                                   ChargeDrEventService eventService,
                                                   ChargeDeclarationService declarationService,
                                                   Clock operationsClock) {
        return new PlanDispatchService(aggregatePlanService, chargePileCommandService,
                eventService, declarationService, operationsClock);
    }
}
