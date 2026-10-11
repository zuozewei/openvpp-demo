package com.openvpp.app.config;

import com.openvpp.dispatch.command.DispatchCommandRepository;
import com.openvpp.dispatch.monitor.ChargeExecutionMonitor;
import com.openvpp.dispatch.monitor.MonitorAlertRepository;
import com.openvpp.iot.thingmodel.ThingModelRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 执行监测装配（第 55 篇接口层底座）：指令仓库 / 监测服务 / 告警仓库 / 物模型注册表。
 *
 * 设计取舍（与 DispatchConfig 同口径）：
 * 1. dispatch 模块服务均为纯 POJO（无 Spring 注解），由启动模块统一装配，
 *    对象图与 dispatch 模块单测一致；
 * 2. 监测三参数走配置：达标容差（教学默认 5 kW，与指令链路一致）、迟到阈值
 *    （默认 30 s：模拟通道采样周期 5 s，健康样点接收零时延，注入迟到 60 s）、
 *    连续达标样点数（默认 3）；
 * 3. 告警仓库独立 Bean：告警是处置证据（确认留痕），与监测评估分列。
 */
@Configuration
public class MonitoringConfig {

    @Bean
    public DispatchCommandRepository dispatchCommandRepository() {
        return new DispatchCommandRepository();
    }

    @Bean
    public MonitorAlertRepository monitorAlertRepository() {
        return new MonitorAlertRepository();
    }

    @Bean
    public ChargeExecutionMonitor chargeExecutionMonitor(
            @Value("${openvpp.monitoring.tolerance-kw:5}") double toleranceKw,
            @Value("${openvpp.monitoring.late-threshold-ms:30000}") long lateThresholdMs,
            @Value("${openvpp.monitoring.steady-sample-count:3}") int steadySampleCount,
            MonitorAlertRepository monitorAlertRepository) {
        return new ChargeExecutionMonitor(toleranceKw, lateThresholdMs,
                steadySampleCount, monitorAlertRepository);
    }

    @Bean
    public ThingModelRegistry thingModelRegistry() {
        return ThingModelRegistry.loadBuiltin();
    }
}
