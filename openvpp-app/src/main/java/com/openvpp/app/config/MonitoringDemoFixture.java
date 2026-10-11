package com.openvpp.app.config;

import com.openvpp.app.orchestration.ChargeMonitoringDemoOrchestrator;
import com.openvpp.app.orchestration.MonitoringDemoRunResult;
import com.openvpp.app.orchestration.MonitoringDemoScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 执行监测演示数据装配（第 55 篇接口层配套，全虚构、不对应任何真实场站或设备）：
 * 启动即跑一次健康场景编排，让 3 个演示账号开箱即可查询「指令 → 回执 → 遥测 →
 * 达标」全链路视图（GET /monitoring/executions 直接有数据）。
 *
 * 口径：每次启动产生一条独立运行（运行标识随机），历史启动的数据不被覆盖 ——
 * 多次重启后查询会看到多次运行的事件（事件标识即运行标识，可互相对拍）。
 * 故障场景（偏差/失联/迟到等）由 POST /monitoring/demo-runs 按需触发，不预置。
 */
@Component
public class MonitoringDemoFixture {

    private static final Logger log = LoggerFactory.getLogger(MonitoringDemoFixture.class);

    private final ChargeMonitoringDemoOrchestrator orchestrator;

    public MonitoringDemoFixture(ChargeMonitoringDemoOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 装配口径：尽力而为（best-effort）。测试上下文以 @MockBean 替换 Clock 时，
     * 启动期种子可能因替身缺省行为不可用 —— 演示数据跳过装配仅告警，不阻断启动；
     * 编排正确性由 OperationsMonitoringApiTest 经接口全链路覆盖。
     */
    @PostConstruct
    void seedMonitoringDemoRun() {
        try {
            MonitoringDemoRunResult result = orchestrator.run(
                    MonitoringDemoScenario.HEALTHY, null);
            log.info("执行监测演示数据已装配: 运行 {} 事件 {} 样点 {} 告警 {} 条",
                    result.getRunId(), result.getEventId(),
                    result.getSamplesIngested(), result.getAlerts().size());
        } catch (RuntimeException e) {
            log.warn("执行监测演示数据装配跳过: {}", e.getMessage());
        }
    }
}
