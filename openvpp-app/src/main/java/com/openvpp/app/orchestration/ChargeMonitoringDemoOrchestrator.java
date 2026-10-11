package com.openvpp.app.orchestration;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.aggregator.plan.AllocationLine;
import com.openvpp.aggregator.plan.DirectionalTargetConverter;
import com.openvpp.aggregator.plan.PlanLevel;
import com.openvpp.app.config.ChargeDemoFixture;
import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.dispatch.command.ChargePileCommandService;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.dispatch.command.DispatchCommandRepository;
import com.openvpp.dispatch.monitor.ChargeExecutionMonitor;
import com.openvpp.dispatch.monitor.CommandMonitorLink;
import com.openvpp.dispatch.monitor.ExecutionEvaluation;
import com.openvpp.dispatch.monitor.MonitorAlert;
import com.openvpp.iot.simulate.ChargePileDownlinkChannel;
import com.openvpp.iot.simulate.SimulatedChargePileChannel;
import com.openvpp.iot.simulate.SimulatedTelemetrySample;
import com.openvpp.iot.thingmodel.ThingModel;
import com.openvpp.iot.thingmodel.ThingModelRegistry;
import com.openvpp.market.charge.DrDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 充电执行监测演示编排 —— 把第 54 篇计划/指令链路与第 55 篇监测域串成一条
 * 可 curl 复跑的完整链路：AggregatePlan 人工确认 → 单桩指令下发 → 模拟设备
 * 执行与遥测回流 → 质量判定 → 方向性告警与核查（3.5.1 业务链路的监测段）。
 *
 * 编排口径：
 * <ol>
 *   <li>计划：二级（运营商→桩）演示计划两行，目标功率一律经
 *       {@link DirectionalTargetConverter} 方向换算（不手写、不混写调节量），
 *       缺口为 0 后经 {@link AggregatePlan#confirm} 人工确认 —— 无确认不派单；</li>
 *   <li>下发：每桩一台 {@link SimulatedChargePileChannel}（场景档案 + 固定种子，
 *       产出确定可复现），指令先登记后下发（失联也有发送证据）；</li>
 *   <li>遥测：先产指令前基线样点（下发后到达平台，标记 beforeCommandSent，
 *       不作达标证明），指令生效后再产响应期样点 —— 曲线含指令前后两段；</li>
 *   <li>监测：样点带场站声明入库（同桩号跨场站双键隔离），评估走
 *       {@link ChargeExecutionMonitor#scan}（幂等），告警随评估产生。</li>
 * </ol>
 *
 * 全虚构教学数据：演示桩 pile-cs01-001/002、演示事件与演示计划均为本编排自造。
 * 教学实现单 JVM 内存版（synchronized 串行化），多次运行按运行标识隔离。
 */
@Service
public class ChargeMonitoringDemoOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChargeMonitoringDemoOrchestrator.class);

    /** 演示场站：沿用演示数据装配的场站一（cs-station-01） */
    public static final String DEMO_STATION = ChargeDemoFixture.STATION_ONE;
    /** 演示桩一（120 kW 档） */
    public static final String PILE_ONE = "pile-cs01-001";
    /** 演示桩二（120 kW 档） */
    public static final String PILE_TWO = "pile-cs01-002";

    /** 指令前基线样点数（3 × 5s = 15 s 基线期） */
    private static final int PRE_COMMAND_SAMPLES = 3;
    /** 响应期样点数（10 × 5s = 50 s 响应期） */
    private static final int POST_COMMAND_SAMPLES = 10;
    /** 指令发送相对基线起点的偏移（ms）：基线样点全部早于发送时刻 */
    private static final long SENT_OFFSET_MS = 15_000L;
    /** 演示通道固定种子基数：同一（场景, 种子）产出确定，可对拍 */
    private static final long DEMO_SEED = 20261008L;

    private final DispatchCommandRepository commandRepository;
    private final ChargeExecutionMonitor monitor;
    private final ThingModelRegistry thingModelRegistry;
    private final Clock clock;

    public ChargeMonitoringDemoOrchestrator(DispatchCommandRepository commandRepository,
                                            ChargeExecutionMonitor monitor,
                                            ThingModelRegistry thingModelRegistry,
                                            Clock clock) {
        this.commandRepository = Objects.requireNonNull(commandRepository, "指令仓库不能为空");
        this.monitor = Objects.requireNonNull(monitor, "执行监测不能为空");
        this.thingModelRegistry = Objects.requireNonNull(thingModelRegistry, "物模型注册表不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 运行一次演示编排：建计划（确认）→ 逐桩下发 → 遥测回流 → 监测评估。
     *
     * @param scenario  演示场景（null = HEALTHY）
     * @param direction 事件方向（null = 场景缺省方向）
     */
    public synchronized MonitoringDemoRunResult run(MonitoringDemoScenario scenario, DrDirection direction) {
        MonitoringDemoScenario effectiveScenario = scenario == null ? MonitoringDemoScenario.HEALTHY : scenario;
        DrDirection effectiveDirection = direction != null ? direction : effectiveScenario.defaultDirection();
        String runId = "MON-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("执行监测演示运行: {} 场景 {} 方向 {}", runId, effectiveScenario, effectiveDirection);

        LocalDateTime now = LocalDateTime.now(clock);
        AggregatePlan plan = buildConfirmedPlan(runId, effectiveScenario, effectiveDirection, now);
        ThingModel model = thingModelRegistry.require("charger-pile-v1");
        AtomicLong runClock = new AtomicLong(System.currentTimeMillis());
        // 设备侧样点钟起步于构造时刻（runClock 当前值）：基线样点天然早于发送时刻
        Map<String, SimulatedChargePileChannel> channels = buildPileChannels(plan, effectiveScenario, model, runClock);

        List<DispatchCommand> commands = dispatchToPiles(plan, channels, runClock);
        int[] counters = flowTelemetry(commands, channels);
        monitor.scan();
        // 本次运行的评估与告警：scan 全量评估（幂等同步告警），结果按本事件收窄
        List<ExecutionEvaluation> evaluations = monitor.queryExecutions(
                plan.getEventId(), plan.getOrganizerTenantId(), null, null);
        List<MonitorAlert> alerts = monitor.listAlerts(plan.getEventId(),
                plan.getOrganizerTenantId(), null, null);
        log.info("执行监测演示完成: {} 样点 {} 缺口 {} 告警 {} 条",
                runId, counters[0], counters[1], alerts.size());
        return new MonitoringDemoRunResult(runId, plan.getEventId(), plan.getPlanId(),
                effectiveDirection.name(), effectiveScenario.name(),
                counters[0], counters[1], evaluations, alerts);
    }

    // ---------------- 内部：四段编排 ----------------

    /** 建二级演示计划并人工确认：目标经方向换算器产出，缺口 0，确认留痕 */
    private AggregatePlan buildConfirmedPlan(String runId, MonitoringDemoScenario scenario,
                                             DrDirection direction, LocalDateTime at) {
        PileSpec[] specs = pileSpecsOf(scenario, direction);
        List<AllocationLine> lines = new ArrayList<>();
        BigDecimal totalAdjust = BigDecimal.ZERO;
        BigDecimal totalBaseline = BigDecimal.ZERO;
        for (PileSpec spec : specs) {
            BigDecimal target = DirectionalTargetConverter.toTargetPower(direction, spec.baselineKw, spec.adjustKw);
            lines.add(AllocationLine.pileLine(spec.resourceId, DEMO_STATION,
                    spec.adjustKw, spec.baselineKw, target, ChargeDemoFixture.DEMO_ASSESS_VERSION));
            totalAdjust = totalAdjust.add(spec.adjustKw);
            totalBaseline = totalBaseline.add(spec.baselineKw);
        }
        AggregatePlan plan = new AggregatePlan(runId + "-PLAN", runId + "-PARENT", runId,
                AccountRepository.DEMO_TENANT, PlanLevel.OPERATOR_TO_PILE, 1, direction,
                totalAdjust, totalBaseline, BigDecimal.ZERO, lines, "monitoring-demo", at);
        plan.confirm("monitoring-demo", at.plusSeconds(1));
        log.info("演示计划已确认: {} v{} 方向 {} 调节量 {} kW（{} 行，{}）",
                plan.getPlanId(), plan.getVersion(), direction, totalAdjust,
                lines.size(), scenario.getDescription());
        return plan;
    }

    /** 逐桩建模拟通道：场景档案 + 固定种子（桩号入种子，两台设备行为可区分且可复现） */
    private Map<String, SimulatedChargePileChannel> buildPileChannels(
            AggregatePlan plan, MonitoringDemoScenario scenario, ThingModel model, AtomicLong runClock) {
        Map<String, SimulatedChargePileChannel> channels = new LinkedHashMap<>();
        for (AllocationLine line : plan.getLines()) {
            channels.put(line.getSubjectId(), new SimulatedChargePileChannel(
                    line.getSubjectId(), model, scenario.profile(),
                    DEMO_SEED + line.getSubjectId().hashCode(), runClock::get));
        }
        return channels;
    }

    /** 逐桩下发指令（先登记后下发）：发送时标（epoch ms）同步给监测关联作证据时界 */
    private List<DispatchCommand> dispatchToPiles(AggregatePlan plan,
                                                  Map<String, SimulatedChargePileChannel> channels,
                                                  AtomicLong runClock) {
        ChargePileCommandService commandService = new ChargePileCommandService(
                commandRepository, routingChannel(channels));
        // 两台桩同一发送时刻：一次推进发送钟，指令证据时界一致
        runClock.addAndGet(SENT_OFFSET_MS);
        long sentAtMs = runClock.get();
        List<DispatchCommand> commands = new ArrayList<>();
        int index = 0;
        for (AllocationLine line : plan.getLines()) {
            index++;
            DispatchCommand command = commandService.issue(new DispatchCommand(
                    plan.getEventId() + "-C" + index, plan.getPlanId(), plan.getVersion(),
                    line.getSubjectId(), line.getStationId(),
                    line.getAdjustKw(), line.getBaselineKw(), line.getTargetPowerKw(),
                    LocalDateTime.now(clock)));
            monitor.link(plan.getEventId(), plan.getOrganizerTenantId(),
                    plan.getDirection(), command, sentAtMs);
            commands.add(command);
        }
        return commands;
    }

    /** 遥测回流：指令前基线样点（后到达，不作达标证明）→ 响应期样点；返回 [样点数, 缺口数] */
    private int[] flowTelemetry(List<DispatchCommand> commands,
                                Map<String, SimulatedChargePileChannel> channels) {
        int samples = 0;
        int gaps = 0;
        for (DispatchCommand command : commands) {
            SimulatedChargePileChannel pileChannel = channels.get(command.getResourceId());
            // 指令前基线样点：采样早于发送、下发后才到达平台 —— 标记 beforeCommandSent
            List<SimulatedTelemetrySample> preCommand = pileChannel.generateSamples(PRE_COMMAND_SAMPLES);
            monitor.ingestAll(DEMO_STATION, preCommand);
            // 响应期样点：围绕生效目标波动（场景档案决定注入）
            List<SimulatedTelemetrySample> postCommand = pileChannel.generateSamples(POST_COMMAND_SAMPLES);
            monitor.ingestAll(DEMO_STATION, postCommand);
            samples += preCommand.size() + postCommand.size();

            CommandMonitorLink link = monitor.requireLink(command.getCommandNo());
            gaps += link.getGaps().size();
        }
        return new int[]{samples, gaps};
    }

    /** 按桩号路由到本运行的模拟通道（运行自造设备，运行间不共享设备状态 —— 干净复跑口径） */
    private static ChargePileDownlinkChannel routingChannel(Map<String, SimulatedChargePileChannel> channels) {
        return (resourceId, targetKw, commandNo) -> {
            SimulatedChargePileChannel target = channels.get(resourceId);
            if (target == null) {
                throw new IllegalArgumentException("未接入的演示桩: " + resourceId);
            }
            return target.sendTargetPower(resourceId, targetKw, commandNo);
        };
    }

    /** 演示两行桩的目标口径（数值全为教学设定）：零目标场景调节量 = 基线（压到 0） */
    private static PileSpec[] pileSpecsOf(MonitoringDemoScenario scenario, DrDirection direction) {
        if (scenario == MonitoringDemoScenario.ZERO_TARGET) {
            return new PileSpec[]{
                    new PileSpec(PILE_ONE, new BigDecimal("120"), new BigDecimal("120")),
                    new PileSpec(PILE_TWO, new BigDecimal("80"), new BigDecimal("80"))};
        }
        if (direction == DrDirection.VALLEY_FILL) {
            return new PileSpec[]{
                    new PileSpec(PILE_ONE, new BigDecimal("40"), new BigDecimal("60")),
                    new PileSpec(PILE_TWO, new BigDecimal("30"), new BigDecimal("50"))};
        }
        return new PileSpec[]{
                new PileSpec(PILE_ONE, new BigDecimal("120"), new BigDecimal("75")),
                new PileSpec(PILE_TWO, new BigDecimal("80"), new BigDecimal("45"))};
    }

    /** 桩目标口径行：基线 + 调节量（目标功率由方向换算器产出） */
    private static final class PileSpec {
        private final String resourceId;
        private final BigDecimal baselineKw;
        private final BigDecimal adjustKw;

        private PileSpec(String resourceId, BigDecimal baselineKw, BigDecimal adjustKw) {
            this.resourceId = resourceId;
            this.baselineKw = baselineKw;
            this.adjustKw = adjustKw;
        }
    }
}
