package com.openvpp.app.orchestration;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.aggregator.plan.AllocationLine;
import com.openvpp.aggregator.plan.DirectionalTargetConverter;
import com.openvpp.aggregator.plan.PlanLevel;
import com.openvpp.app.config.ChargeDemoFixture;
import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.dispatch.command.ChargePileCommandService;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.dispatch.command.DispatchCommandRepository;
import com.openvpp.dispatch.monitor.ChargeExecutionMonitor;
import com.openvpp.dispatch.monitor.CommandMonitorLink;
import com.openvpp.dispatch.monitor.SampleGap;
import com.openvpp.iot.simulate.ChargePileDownlinkChannel;
import com.openvpp.iot.simulate.SimulatedChargePileChannel;
import com.openvpp.iot.simulate.SimulatedTelemetrySample;
import com.openvpp.iot.thingmodel.ThingModel;
import com.openvpp.iot.thingmodel.ThingModelRegistry;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDeclarationService;
import com.openvpp.market.charge.ChargeDrEvent;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.settlement.assess.AssessmentRecord;
import com.openvpp.settlement.assess.AssessmentRule;
import com.openvpp.settlement.assess.AssessmentService;
import com.openvpp.settlement.assess.BaselineFreeze;
import com.openvpp.settlement.assess.BaselineFreezeRegistry;
import com.openvpp.settlement.assess.FrozenBaselinePoint;
import com.openvpp.settlement.assess.MeteredSample;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 效果评估演示编排 —— 把第 53 篇事件/申报链路与第 56 篇评估域串成一条
 * 可 curl 复跑的完整链路（3.5.1 业务链路的评估段，编排次序即工序 3 口径）：
 *
 * <ol>
 *   <li>事件发布：建事件（方向/响应窗/目标）→ 发布 → 参与授权演示场站；</li>
 *   <li>快照：能力快照沿用 ChargeDemoFixture 装配（demo-v1），申报按事件方向
 *       取可信容量列校验；</li>
 *   <li>申报 → 确认：场站申报目标容量（请求标识幂等），运营商确认 —— 只有
 *       CONFIRMED 申报才进入后续冻结与派单；</li>
 *   <li>响应前基线冻结（本道工序核心）：对每桩冻结核算参照系 —— 冻结时刻、
 *       样本来源（已确认申报基线）、规则版本、基线版本齐备，冻结早于响应窗口，
 *       锁后拒写由登记簿强制；</li>
 *   <li>派单下发 → 模拟遥测：复用第 54/55 篇链路（计划确认 → 指令下发 →
 *       指令前基线样点 + 响应期样点 → 质量判定）；</li>
 *   <li>事件结束 → 评估（只读冻结基线）：方向性计量只按有效区间积分，
 *       完整度不足自动转人工复核。</li>
 * </ol>
 *
 * 演示时间模型：事件响应窗取编排时刻起 30 s ~ 150 s（真实时钟），设备采样钟
 * 同真实时钟起步 —— 指令前样点落在窗口外（基线期），响应期样点落在窗口内，
 * 冻结时刻早于窗口开始，三道质量门天然成立。全虚构教学数据：演示桩
 * pile-cs01-001/002、演示事件与申报均为本编排自造（数值全为教学设定）。
 * 教学实现单 JVM 内存版（synchronized 串行化），多次运行按运行标识隔离。
 */
@Service
public class ChargeAssessmentDemoOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChargeAssessmentDemoOrchestrator.class);

    /** 演示场站：沿用演示数据装配的场站一（cs-station-01） */
    public static final String DEMO_STATION = ChargeDemoFixture.STATION_ONE;
    /** 演示桩一（120 kW 档） */
    public static final String PILE_ONE = "pile-cs01-001";
    /** 演示桩二（120 kW 档） */
    public static final String PILE_TWO = "pile-cs01-002";

    /** 指令前基线样点数（6 × 5s = 30 s 基线期，全部早于响应窗口） */
    private static final int PRE_COMMAND_SAMPLES = 6;
    /** 响应期样点数（10 × 5s = 50 s 响应期，全部落在响应窗口内） */
    private static final int POST_COMMAND_SAMPLES = 10;
    /** 指令发送相对采样钟起点的偏移（ms）：与基线样点数对齐（6 × 5s），
     *  使响应期首个样点的采样时刻恰为指令发送时刻（证据时界自洽） */
    private static final long SENT_OFFSET_MS = 30_000L;
    /** 事件响应窗：编排时刻起 30 s ~ 150 s（冻结/申报/派单均在窗口前完成） */
    private static final long WINDOW_START_OFFSET_SECONDS = 30;
    private static final long WINDOW_END_OFFSET_SECONDS = 150;
    /** 演示通道固定种子基数：同一（场景, 种子）产出确定，可对拍 */
    private static final long DEMO_SEED = 20261011L;
    /** 冻结样本来源：已确认申报基线（教学口径，争议时按申报单重算） */
    public static final String SAMPLE_SOURCE = "CONFIRMED_DECLARATION_BASELINE";

    private final ChargeDrEventService eventService;
    private final ChargeDeclarationService declarationService;
    private final BaselineFreezeRegistry freezeRegistry;
    private final AssessmentService assessmentService;
    private final AssessmentRule assessmentRule;
    private final DispatchCommandRepository commandRepository;
    private final ChargeExecutionMonitor monitor;
    private final ThingModelRegistry thingModelRegistry;
    private final Clock clock;

    public ChargeAssessmentDemoOrchestrator(ChargeDrEventService eventService,
                                            ChargeDeclarationService declarationService,
                                            BaselineFreezeRegistry freezeRegistry,
                                            AssessmentService assessmentService,
                                            AssessmentRule assessmentRule,
                                            DispatchCommandRepository commandRepository,
                                            ChargeExecutionMonitor monitor,
                                            ThingModelRegistry thingModelRegistry,
                                            Clock clock) {
        this.eventService = Objects.requireNonNull(eventService, "事件服务不能为空");
        this.declarationService = Objects.requireNonNull(declarationService, "申报服务不能为空");
        this.freezeRegistry = Objects.requireNonNull(freezeRegistry, "冻结登记簿不能为空");
        this.assessmentService = Objects.requireNonNull(assessmentService, "评估服务不能为空");
        this.assessmentRule = Objects.requireNonNull(assessmentRule, "评估规则不能为空");
        this.commandRepository = Objects.requireNonNull(commandRepository, "指令仓库不能为空");
        this.monitor = Objects.requireNonNull(monitor, "执行监测不能为空");
        this.thingModelRegistry = Objects.requireNonNull(thingModelRegistry, "物模型注册表不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 运行一次评估演示编排：事件 → 申报 → 确认 → 响应前基线冻结 → 派单 →
     * 遥测 → 事件结束 → 评估（只读冻结基线）。
     *
     * @param scenario  演示场景（null = HEALTHY；遥测故障注入口径同第 55 篇）
     * @param direction 事件方向（null = 场景缺省方向）
     */
    public synchronized AssessmentDemoRunResult run(MonitoringDemoScenario scenario, DrDirection direction) {
        MonitoringDemoScenario effectiveScenario = scenario == null ? MonitoringDemoScenario.HEALTHY : scenario;
        DrDirection effectiveDirection = direction != null ? direction : effectiveScenario.defaultDirection();
        String runId = "ASM-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("效果评估演示运行: {} 场景 {} 方向 {}", runId, effectiveScenario, effectiveDirection);

        // 业务时刻先于采样钟建立：保证响应期首个样点（baseMs+30s）不早于
        // 响应窗口开始（epoch(t0)+30s），冻结质量门与计量窗口对齐成立
        LocalDateTime t0 = LocalDateTime.now(clock);
        AtomicLong runClock = new AtomicLong(System.currentTimeMillis());
        LocalDateTime windowStart = t0.plusSeconds(WINDOW_START_OFFSET_SECONDS);
        LocalDateTime windowEnd = t0.plusSeconds(WINDOW_END_OFFSET_SECONDS);

        PileSpec[] specs = pileSpecsOf(effectiveScenario, effectiveDirection);
        BigDecimal totalAdjust = totalOf(specs, true);
        BigDecimal totalBaseline = totalOf(specs, false);

        // 1. 事件发布
        ChargeDrEvent event = eventService.create(runId, AccountRepository.DEMO_TENANT,
                effectiveDirection, windowStart, windowEnd, totalAdjust, t0.plusSeconds(20), t0);
        eventService.publish(platformScope(), runId, LocalDateTime.now(clock));
        eventService.authorizeParticipation(platformScope(), runId,
                List.of(DEMO_STATION), "assessment-demo", LocalDateTime.now(clock));

        // 2. + 3. 快照（既有装配 demo-v1）→ 申报 → 确认
        ChargeDeclaration declaration = declarationService.declare(platformScope(), runId,
                DEMO_STATION, AccountRepository.DEMO_TENANT, totalAdjust,
                ChargeDemoFixture.DEMO_ASSESS_VERSION, runId + "-REQ", LocalDateTime.now(clock));
        declarationService.confirm(platformScope(), declaration.getDeclarationId(), LocalDateTime.now(clock));
        log.info("演示申报已确认: {} 事件 {} 容量 {} kW 快照版本 {}",
                declaration.getDeclarationId(), runId, totalAdjust, ChargeDemoFixture.DEMO_ASSESS_VERSION);

        // 4. 响应前基线冻结：每桩一行（冻结时刻/样本来源/规则版本/基线版本齐备）
        List<BaselineFreeze> freezes = new ArrayList<>();
        for (PileSpec spec : specs) {
            BaselineFreeze freeze = freezeRegistry.freeze(new BaselineFreeze(
                    runId, AccountRepository.DEMO_TENANT, DEMO_STATION, spec.resourceId,
                    effectiveDirection, LocalDateTime.now(clock), windowStart, windowEnd,
                    SAMPLE_SOURCE, assessmentRule.getRuleVersion(), runId + "-BL-v1",
                    List.of(new FrozenBaselinePoint(runClock.get(), spec.baselineKw.doubleValue()))));
            freezes.add(freeze);
        }

        // 5. 派单下发 + 模拟遥测（复用第 54/55 篇链路机制）
        AggregatePlan plan = buildConfirmedPlan(runId, event, specs, totalAdjust, totalBaseline);
        ThingModel model = thingModelRegistry.require("charger-pile-v1");
        Map<String, SimulatedChargePileChannel> channels = buildPileChannels(plan, effectiveScenario, model, runClock);
        List<DispatchCommand> commands = dispatchToPiles(plan, channels, runClock);
        int[] counters = flowTelemetry(commands, channels);
        monitor.scan();

        // 6. 事件结束 → 7. 评估（只读冻结基线）
        eventService.end(platformScope(), runId, LocalDateTime.now(clock));
        List<AssessmentRecord> assessments = assessAll(event, commands);

        log.info("效果评估演示完成: {} 样点 {} 缺口 {} 冻结 {} 评估 {} 条",
                runId, counters[0], counters[1], freezes.size(), assessments.size());
        return new AssessmentDemoRunResult(runId, event.getEventId(), declaration.getDeclarationId(),
                effectiveDirection.name(), effectiveScenario.name(),
                counters[0], counters[1], freezes, assessments);
    }

    // ---------------- 内部：评估段 ----------------

    /** 逐桩评估：监测样点 → 结算域计量输入（证据资格口径同第 55 篇），只读冻结基线 */
    private List<AssessmentRecord> assessAll(ChargeDrEvent event, List<DispatchCommand> commands) {
        List<AssessmentRecord> assessments = new ArrayList<>();
        for (DispatchCommand command : commands) {
            CommandMonitorLink link = monitor.requireLink(command.getCommandNo());
            List<MeteredSample> metered = link.getSamples().stream()
                    .map(sample -> new MeteredSample(sample.getSampledAtMs(), sample.getPowerKw(),
                            sample.isEvidenceEligible(link.getSentAtMs())))
                    .collect(Collectors.toList());
            // 缺失计数以冻结响应窗为界（计量口径与冻结版本同一条窗）
            long windowStartMs = freezeRegistry
                    .requireFrozen(event.getEventId(), DEMO_STATION, command.getResourceId())
                    .windowStartMs();
            int missing = Math.toIntExact(link.getGaps().stream()
                    .filter(gap -> gap.getBeforeSampledAtMs() >= windowStartMs)
                    .mapToLong(SampleGap::getMissingCount)
                    .sum());
            assessments.add(assessmentService.assess(platformScope(), event,
                    DEMO_STATION, command.getResourceId(), metered, missing,
                    assessmentRule, LocalDateTime.now(clock)));
        }
        return assessments;
    }

    // ---------------- 内部：派单与遥测段（与执行监测演示编排同机制） ----------------

    /** 建二级演示计划并人工确认：目标经方向换算器产出，缺口 0，确认留痕 */
    private AggregatePlan buildConfirmedPlan(String runId, ChargeDrEvent event,
                                             PileSpec[] specs,
                                             BigDecimal totalAdjust, BigDecimal totalBaseline) {
        List<AllocationLine> lines = new ArrayList<>();
        for (PileSpec spec : specs) {
            BigDecimal target = DirectionalTargetConverter.toTargetPower(
                    event.getDirection(), spec.baselineKw, spec.adjustKw);
            lines.add(AllocationLine.pileLine(spec.resourceId, DEMO_STATION,
                    spec.adjustKw, spec.baselineKw, target, ChargeDemoFixture.DEMO_ASSESS_VERSION));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AggregatePlan plan = new AggregatePlan(runId + "-PLAN", runId + "-PARENT", event.getEventId(),
                AccountRepository.DEMO_TENANT, PlanLevel.OPERATOR_TO_PILE, 1, event.getDirection(),
                totalAdjust, totalBaseline, BigDecimal.ZERO, lines, "assessment-demo", now);
        plan.confirm("assessment-demo", now.plusSeconds(1));
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

    /** 遥测回流：指令前基线样点（窗口外，不作计量证据）→ 响应期样点；返回 [样点数, 缺口数] */
    private int[] flowTelemetry(List<DispatchCommand> commands,
                                Map<String, SimulatedChargePileChannel> channels) {
        int samples = 0;
        int gaps = 0;
        for (DispatchCommand command : commands) {
            SimulatedChargePileChannel pileChannel = channels.get(command.getResourceId());
            // 指令前基线样点：采样早于发送、下发后才到达平台 —— 窗口外基线期样点
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

    /** 编排内部的平台范围：编排即平台组织方操作（教学演示，不经登录态） */
    private DataScope platformScope() {
        return DataScopeResolver.resolve(IdentityContext.of(
                "assessment-demo", AccountRepository.DEMO_TENANT,
                RoleType.PLATFORM_ADMIN, Set.of()));
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

    private static BigDecimal totalOf(PileSpec[] specs, boolean adjust) {
        BigDecimal total = BigDecimal.ZERO;
        for (PileSpec spec : specs) {
            total = total.add(adjust ? spec.adjustKw : spec.baselineKw);
        }
        return total;
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
