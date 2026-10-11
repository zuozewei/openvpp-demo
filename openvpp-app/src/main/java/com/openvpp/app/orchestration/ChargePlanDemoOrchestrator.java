package com.openvpp.app.orchestration;

import com.openvpp.aggregator.plan.AggregatePlan;
import com.openvpp.app.config.ChargeDemoFixture;
import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.app.service.ChargePileDeviceYard;
import com.openvpp.app.service.PlanDispatchService;
import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import com.openvpp.dispatch.command.CommandState;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.market.charge.CapabilitySnapshot;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDeclarationService;
import com.openvpp.market.charge.ChargeDrEventService;
import com.openvpp.market.charge.DrDirection;
import com.openvpp.market.charge.SnapshotRegistry;
import com.openvpp.settlement.assess.AssessmentRule;
import com.openvpp.settlement.assess.BaselineFreeze;
import com.openvpp.settlement.assess.BaselineFreezeRegistry;
import com.openvpp.settlement.assess.FrozenBaselinePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 分级派单演示编排（第 54 篇接口层配套）—— 一次运行走完业务链路全段：
 *
 * <ol>
 *   <li>事件发布：建事件（方向/响应窗/目标）→ 发布 → 参与授权两个演示场站；</li>
 *   <li>快照：能力快照沿用 ChargeDemoFixture 装配（demo-v1），申报按事件方向
 *       取可信容量列校验；</li>
 *   <li>申报 → 确认：两个演示场站按方向申报目标容量（削峰 600+180 / 填谷 800+240，
 *       均为演示快照可信容量列上限），运营商确认 —— 只有 CONFIRMED 申报进计划；</li>
 *   <li>响应前基线冻结：每站一行（冻结时刻/样本来源/规则版本/基线版本齐备），
 *       冻结早于响应窗口 3 分钟，三道质量门天然成立（第 56 篇口径）；</li>
 *   <li>构建分级计划：申报截止即派单（显式以截止时刻为派单时刻，不经 sleep），
 *       顶层运营商份额 + 子计划单桩调节量与绝对目标功率（缺口 0）；</li>
 *   <li>人工确认 → 派单下发 → 指令回执：经设备院子下发绝对目标功率，健康场景
 *       全部受理；OFFLINE 场景演示场站二失联，指令无回执进入核查。</li>
 * </ol>
 *
 * 演示时间模型：响应窗取编排时刻起 180 s ~ 600 s，申报截止取编排时刻起 30 s ——
 * 真实时钟下申报/冻结/派单均在窗口前完成。全虚构教学数据：演示事件与申报均为
 * 本编排自造（数值全为教学设定）。教学实现单 JVM 内存版（synchronized 串行化），
 * 多次运行按运行标识隔离；设备档案 finally 复位，保证干净复跑。
 */
@Service
public class ChargePlanDemoOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChargePlanDemoOrchestrator.class);

    /** 演示场站一：20 桩 × 120 kW（申报口径随事件方向取演示快照可信容量列） */
    public static final String DEMO_STATION_ONE = ChargeDemoFixture.STATION_ONE;
    /** 演示场站二：12 桩 × 60 kW */
    public static final String DEMO_STATION_TWO = ChargeDemoFixture.STATION_TWO;

    /** 削峰演示申报：场站一 600 kW（削峰可信列上限）+ 场站二 180 kW */
    private static final BigDecimal PEAK_DECLARE_ONE = new BigDecimal("600");
    private static final BigDecimal PEAK_DECLARE_TWO = new BigDecimal("180");
    /** 填谷演示申报：场站一 800 kW（填谷可信列上限）+ 场站二 240 kW */
    private static final BigDecimal VALLEY_DECLARE_ONE = new BigDecimal("800");
    private static final BigDecimal VALLEY_DECLARE_TWO = new BigDecimal("240");
    /** 事件响应窗：编排时刻起 180 s ~ 600 s（冻结/申报/派单均在窗口前完成） */
    private static final long WINDOW_START_OFFSET_SECONDS = 180;
    private static final long WINDOW_END_OFFSET_SECONDS = 600;
    /** 申报截止：编排时刻起 30 s（派单显式取截止时刻，不经 sleep） */
    private static final long DECLARE_DEADLINE_OFFSET_SECONDS = 30;
    /** 冻结样本来源：已确认申报基线（教学口径，与评估编排同源） */
    private static final String SAMPLE_SOURCE = ChargeAssessmentDemoOrchestrator.SAMPLE_SOURCE;

    private final ChargeDrEventService eventService;
    private final ChargeDeclarationService declarationService;
    private final SnapshotRegistry snapshotRegistry;
    private final BaselineFreezeRegistry freezeRegistry;
    private final AssessmentRule assessmentRule;
    private final PlanDispatchService dispatchService;
    private final ChargePileDeviceYard deviceYard;
    private final Clock clock;

    public ChargePlanDemoOrchestrator(ChargeDrEventService eventService,
                                      ChargeDeclarationService declarationService,
                                      SnapshotRegistry snapshotRegistry,
                                      BaselineFreezeRegistry freezeRegistry,
                                      AssessmentRule assessmentRule,
                                      PlanDispatchService dispatchService,
                                      ChargePileDeviceYard deviceYard,
                                      Clock clock) {
        this.eventService = Objects.requireNonNull(eventService, "事件服务不能为空");
        this.declarationService = Objects.requireNonNull(declarationService, "申报服务不能为空");
        this.snapshotRegistry = Objects.requireNonNull(snapshotRegistry, "快照登记簿不能为空");
        this.freezeRegistry = Objects.requireNonNull(freezeRegistry, "冻结登记簿不能为空");
        this.assessmentRule = Objects.requireNonNull(assessmentRule, "评估规则不能为空");
        this.dispatchService = Objects.requireNonNull(dispatchService, "派单门面不能为空");
        this.deviceYard = Objects.requireNonNull(deviceYard, "设备院子不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 运行一次分级派单演示：事件 → 申报 → 确认 → 响应前基线冻结 → 构建计划 →
     * 确认计划 → 下发 → 指令回执。
     *
     * @param scenario  演示场景（null = HEALTHY；OFFLINE = 场站二失联进核查）
     * @param direction 事件方向（null = 场景缺省方向）
     */
    public synchronized PlanDemoRunResult run(MonitoringDemoScenario scenario, DrDirection direction) {
        MonitoringDemoScenario effectiveScenario = scenario == null ? MonitoringDemoScenario.HEALTHY : scenario;
        DrDirection effectiveDirection = direction != null ? direction : effectiveScenario.defaultDirection();
        String runId = "PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("分级派单演示运行: {} 场景 {} 方向 {}", runId, effectiveScenario, effectiveDirection);

        LocalDateTime t0 = LocalDateTime.now(clock);
        LocalDateTime windowStart = t0.plusSeconds(WINDOW_START_OFFSET_SECONDS);
        LocalDateTime windowEnd = t0.plusSeconds(WINDOW_END_OFFSET_SECONDS);
        LocalDateTime declareDeadline = t0.plusSeconds(DECLARE_DEADLINE_OFFSET_SECONDS);
        boolean valley = effectiveDirection == DrDirection.VALLEY_FILL;
        BigDecimal declareOne = valley ? VALLEY_DECLARE_ONE : PEAK_DECLARE_ONE;
        BigDecimal declareTwo = valley ? VALLEY_DECLARE_TWO : PEAK_DECLARE_TWO;
        BigDecimal target = declareOne.add(declareTwo);

        // 1. 事件发布（两个演示场站纳入申报范围）
        eventService.create(runId, AccountRepository.DEMO_TENANT, effectiveDirection,
                windowStart, windowEnd, target, declareDeadline, t0);
        eventService.publish(platformScope(), runId, LocalDateTime.now(clock));
        eventService.authorizeParticipation(platformScope(), runId,
                List.of(DEMO_STATION_ONE, DEMO_STATION_TWO), "plan-demo", LocalDateTime.now(clock));

        // 2. + 3. 快照（既有装配 demo-v1）→ 申报 → 确认
        declareAndConfirm(runId, DEMO_STATION_ONE, declareOne, runId + "-R1");
        declareAndConfirm(runId, DEMO_STATION_TWO, declareTwo, runId + "-R2");

        // 4. 响应前基线冻结：每站一行（冻结早于响应窗口，样本采样早于窗口开始）
        List<BaselineFreeze> freezes = new ArrayList<>();
        freezes.add(freeze(runId, DEMO_STATION_ONE, effectiveDirection, windowStart, windowEnd));
        freezes.add(freeze(runId, DEMO_STATION_TWO, effectiveDirection, windowStart, windowEnd));

        // 5. 构建分级计划：申报截止即派单（显式截止时刻，口径见编排类注释）
        AggregatePlan plan = dispatchService.buildPlan(
                platformScope(), runId, "plan-demo", declareDeadline);
        if (plan.getGapKw().signum() > 0) {
            throw new IllegalStateException("演示计划出现缺口 " + plan.getGapKw()
                    + " kW（申报设置与目标不匹配，演示编排自洽性被破坏）");
        }

        // 6. 人工确认（整树）→ 7. 派单下发 → 指令回执
        dispatchService.confirm(platformScope(), plan.getPlanId(), "plan-demo");
        String offlineResource = snapshotResourceOf(DEMO_STATION_TWO);
        try {
            if (effectiveScenario == MonitoringDemoScenario.OFFLINE) {
                // 失联演示：场站二设备档案切失联，下行无回执进入核查
                deviceYard.applyProfile(offlineResource, MonitoringDemoScenario.OFFLINE.profile());
            }
            PlanDispatchService.SendResult sendResult = dispatchService.send(
                    platformScope(), plan.getPlanId(), "plan-demo");
            List<DispatchCommand> commands = sendResult.getCommands();
            log.info("分级派单演示完成: {} 计划 {} 子计划 {} 条 指令 {} 条（失联 {} 条）",
                    runId, plan.getPlanId(), sendResult.getSentPlans().size(), commands.size(),
                    commands.stream().filter(c -> c.getState() == CommandState.NO_RECEIPT).count());
            return new PlanDemoRunResult(runId, runId, effectiveDirection.name(),
                    effectiveScenario.name(), plan.getPlanId(), plan.getGapKw(),
                    freezes.size(), sendResult.getSentPlans(), commands);
        } finally {
            // 设备档案复位：多次编排与后续演示共用设备院子，干净复跑口径
            deviceYard.resetProfile(snapshotResourceOf(DEMO_STATION_ONE));
            deviceYard.resetProfile(offlineResource);
        }
    }

    // ---------------- 内部 ----------------

    private ChargeDeclaration declareAndConfirm(String runId, String stationId,
                                                BigDecimal declaredKw, String requestId) {
        ChargeDeclaration declaration = declarationService.declare(platformScope(), runId,
                stationId, AccountRepository.DEMO_TENANT, declaredKw,
                ChargeDemoFixture.DEMO_ASSESS_VERSION, requestId, LocalDateTime.now(clock));
        declarationService.confirm(platformScope(), declaration.getDeclarationId(), LocalDateTime.now(clock));
        return declaration;
    }

    private BaselineFreeze freeze(String runId, String stationId, DrDirection direction,
                                  LocalDateTime windowStart, LocalDateTime windowEnd) {
        CapabilitySnapshot snapshot = snapshotRegistry.assertUsable(
                stationId, ChargeDemoFixture.DEMO_ASSESS_VERSION, LocalDateTime.now(clock));
        return freezeRegistry.freeze(new BaselineFreeze(
                runId, AccountRepository.DEMO_TENANT, stationId, snapshot.getResourceId(),
                direction, LocalDateTime.now(clock), windowStart, windowEnd,
                SAMPLE_SOURCE, assessmentRule.getRuleVersion(), runId + "-BL-v1",
                List.of(new FrozenBaselinePoint(
                        System.currentTimeMillis(), snapshot.getBaselineKw().doubleValue()))));
    }

    private String snapshotResourceOf(String stationId) {
        return snapshotRegistry.assertUsable(
                stationId, ChargeDemoFixture.DEMO_ASSESS_VERSION, LocalDateTime.now(clock)).getResourceId();
    }

    /** 编排内部的平台范围：编排即平台组织方操作（教学演示，不经登录态） */
    private DataScope platformScope() {
        return DataScopeResolver.resolve(IdentityContext.of(
                "plan-demo", AccountRepository.DEMO_TENANT,
                RoleType.PLATFORM_ADMIN, Set.of()));
    }
}
