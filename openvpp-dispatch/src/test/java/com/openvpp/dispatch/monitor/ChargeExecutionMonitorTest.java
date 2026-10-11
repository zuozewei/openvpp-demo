package com.openvpp.dispatch.monitor;

import com.openvpp.dispatch.command.ChargePileCommandService;
import com.openvpp.dispatch.command.CommandState;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.dispatch.command.DispatchCommandRepository;
import com.openvpp.iot.simulate.ChargePileDownlinkChannel;
import com.openvpp.iot.simulate.CommandReceipt;
import com.openvpp.iot.simulate.FaultInjectionProfile;
import com.openvpp.iot.simulate.SimulatedChargePileChannel;
import com.openvpp.iot.simulate.SimulatedTelemetrySample;
import com.openvpp.iot.simulate.TelemetryDataSource;
import com.openvpp.iot.thingmodel.ThingModel;
import com.openvpp.iot.thingmodel.ThingModelRegistry;
import com.openvpp.market.charge.DrDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 充电执行监测单测（第 55 篇底座，对应"监测与质量"验收格）：
 *
 * 零目标绝对偏差、方向性超差两方向、重复/迟到/乱序/缺失注入识别（监测方独立判定，
 * 不采信注入标记）、指令前样点不算达标、失联进入核查状态、告警确认留痕、
 * 同桩号跨场站隔离、缺失不补 0、回执≠到位≠连续达标三列分列。
 */
class ChargeExecutionMonitorTest {

    private static final String STATION = "cs-station-01";
    private static final String RESOURCE = "pile-cs01-001";
    private static final String EVENT = "EVT-MON-TEST";
    private static final String TENANT = "tenant-openvpp";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 8, 13, 45);
    private static final long BASE_MS = 1_800_000_000_000L;
    private static final double TOLERANCE_KW = 5.0;

    /** 通道桩：默认受理（回显本指令目标），offline=true 即失联（无回执） */
    private static final class StubChannel implements ChargePileDownlinkChannel {
        private boolean offline;
        private long ackedAtMs = BASE_MS;

        @Override
        public Optional<CommandReceipt> sendTargetPower(String resourceId, BigDecimal targetKw, String commandNo) {
            if (offline) {
                return Optional.empty();
            }
            return Optional.of(new CommandReceipt(commandNo, resourceId, targetKw,
                    CommandReceipt.Status.ACCEPTED, null, ackedAtMs));
        }
    }

    private StubChannel channel;
    private ChargePileCommandService commandService;
    private MonitorAlertRepository alertRepository;
    private ChargeExecutionMonitor monitor;

    @BeforeEach
    void setUp() {
        channel = new StubChannel();
        commandService = new ChargePileCommandService(new DispatchCommandRepository(), channel);
        alertRepository = new MonitorAlertRepository();
        monitor = new ChargeExecutionMonitor(TOLERANCE_KW, 30_000L, 3, alertRepository);
    }

    // ---------- 构造辅助 ----------

    /** 手工构造采集样点：不携带任何注入标记（质量判定必须由监测方独立做出） */
    private static SimulatedTelemetrySample rawSample(String resourceId, long seq,
                                                      long sampledAtMs, long receivedAtMs,
                                                      double powerKw) {
        return new SimulatedTelemetrySample(resourceId, seq, sampledAtMs, receivedAtMs,
                powerKw, TelemetryDataSource.SIMULATED, Collections.emptySet());
    }

    private DispatchCommand ackedCommand(String commandNo, String stationId, String resourceId,
                                         String baselineKw, String adjustKw, String targetKw,
                                         long sentAtMs) {
        DispatchCommand command = commandService.issue(new DispatchCommand(
                commandNo, "PLAN-MON-1", 1, resourceId, stationId,
                new BigDecimal(adjustKw), new BigDecimal(baselineKw), new BigDecimal(targetKw),
                CREATED_AT));
        assertEquals(CommandState.ACKED, command.getState());
        monitor.link(EVENT, TENANT, DrDirection.PEAK_SHAVE, command, sentAtMs);
        return command;
    }

    private long countMarks(List<MonitoredSample> samples, SampleQualityMark mark) {
        return samples.stream().filter(s -> s.getQualityMarks().contains(mark)).count();
    }

    // ---------- 零目标：绝对偏差 ----------

    @Test
    void 零目标有实际功率按绝对偏差判超差并告警() {
        DispatchCommand command = ackedCommand("CMD-ZERO-1", STATION, RESOURCE,
                "120", "120", "0", BASE_MS);

        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 8.0));
        ExecutionEvaluation evaluation = monitor.evaluate("CMD-ZERO-1");

        assertNotNull(evaluation.getDeviation(), "有有效证据时必须给出偏差结论");
        assertEquals(DeviationBasis.ABSOLUTE_ZERO_TARGET, evaluation.getDeviation().getBasis(),
                "零目标必须用绝对偏差（比例偏差除零无定义，会掩盖异常）");
        assertTrue(evaluation.getDeviation().isExceeded(), "|8 − 0| = 8 > 容差 5，必须超差");
        assertEquals(8.0, evaluation.getDeviation().getDeviationKw(), 1e-9,
                "偏差值是绝对偏差 8 − 0");
        assertEquals(1, evaluation.getAlerts().size());
        assertEquals(MonitorAlertType.DIRECTIONAL_DEVIATION, evaluation.getAlerts().get(0).getType());
        assertEquals(DrDirection.PEAK_SHAVE, evaluation.getAlerts().get(0).getDirection());
        assertNotNull(evaluation.getAlerts().get(0).getEvidence(), "告警必须携带样点证据");
    }

    @Test
    void 零目标且实测贴近零为达标不报超差() {
        DispatchCommand command = ackedCommand("CMD-ZERO-2", STATION, RESOURCE,
                "120", "120", "0", BASE_MS);
        for (int i = 1; i <= 3; i++) {
            monitor.ingest(STATION, rawSample(RESOURCE, i, BASE_MS + i * 1_000, BASE_MS + i * 1_000, 1.5));
        }
        ExecutionEvaluation evaluation = monitor.evaluate("CMD-ZERO-2");
        assertTrue(evaluation.isAttained(), "零目标下 |1.5 − 0| ≤ 容差，有效零功率运行属达标");
        assertTrue(evaluation.isSteadyAttained(), "3 个连续有效样点落带 → 连续达标");
        assertFalse(evaluation.getDeviation().isExceeded());
        assertEquals(0, evaluation.getAlerts().size());
    }

    // ---------- 方向性超差：两方向 ----------

    @Test
    void 削峰方向实测偏高判压不下来并告警() {
        DispatchCommand command = ackedCommand("CMD-PEAK-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 56.0));

        ExecutionEvaluation evaluation = monitor.evaluate("CMD-PEAK-1");
        assertEquals(DrDirection.PEAK_SHAVE, evaluation.getDeviation().getDirection());
        assertEquals(DeviationBasis.DIRECTIONAL, evaluation.getDeviation().getBasis());
        assertEquals(11.0, evaluation.getDeviation().getDeviationKw(), 1e-9);
        assertTrue(evaluation.getDeviation().isExceeded(), "削峰：实测 56 > 目标 45 + 容差 5");
        assertEquals(1, evaluation.getAlerts().size());
        assertEquals(MonitorAlertType.DIRECTIONAL_DEVIATION, evaluation.getAlerts().get(0).getType());
    }

    @Test
    void 填谷方向实测偏低判抬不上去并告警() {
        // 填谷：基线 40 + 调节 60 = 目标 100
        DispatchCommand command = commandService.issue(new DispatchCommand(
                "CMD-VALLEY-1", "PLAN-MON-1", 1, RESOURCE, STATION,
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("100"), CREATED_AT));
        monitor.link(EVENT, TENANT, DrDirection.VALLEY_FILL, command, BASE_MS);

        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 88.0));
        ExecutionEvaluation evaluation = monitor.evaluate("CMD-VALLEY-1");

        assertEquals(DrDirection.VALLEY_FILL, evaluation.getDeviation().getDirection());
        assertEquals(-12.0, evaluation.getDeviation().getDeviationKw(), 1e-9);
        assertTrue(evaluation.getDeviation().isExceeded(), "填谷：实测 88 < 目标 100 − 容差 5");
        assertEquals(1, evaluation.getAlerts().size());
        assertEquals(DrDirection.VALLEY_FILL, evaluation.getAlerts().get(0).getDirection(),
                "告警方向必须与事件方向一致");
    }

    @Test
    void 削峰实测低于目标不告警且重复扫描不重复告警() {
        DispatchCommand command = ackedCommand("CMD-PEAK-UNDER-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        // 削峰实测 35：压过头（过度响应），不属本篇"压不下来"告警口径
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 35.0));
        monitor.ingest(STATION, rawSample(RESOURCE, 2, BASE_MS + 2_000, BASE_MS + 3_000, 34.0));

        ExecutionEvaluation first = monitor.evaluate("CMD-PEAK-UNDER-1");
        assertFalse(first.getDeviation().isExceeded(), "削峰方向负偏差不触发超差告警");
        assertEquals(0, first.getAlerts().size());

        monitor.scan();
        monitor.scan();
        assertEquals(0, alertRepository.listAll().size(), "重复扫描不得产生重复告警");
    }

    // ---------- 重复/迟到/乱序/缺失：监测方独立识别 ----------

    @Test
    void 监测方独立识别迟到不采信注入标记() {
        DispatchCommand command = ackedCommand("CMD-LATE-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        // 手工样点不带任何 TelemetryAnomaly 标记，但接收时标晚 60 秒（阈值 30 秒）
        MonitoredSample ingested = monitor.ingest(STATION,
                rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 61_000, 45.0));
        assertTrue(ingested.getQualityMarks().contains(SampleQualityMark.LATE),
                "迟到必须由监测方按双时标独立判定");

        ExecutionEvaluation evaluation = monitor.evaluate("CMD-LATE-1");
        assertFalse(evaluation.isAttained(), "迟到样点不能充当本指令达标证明");
        assertNull(evaluation.getDeviation(), "证据为空不下偏差结论");
    }

    @Test
    void 故障注入通道的重复乱序缺失均被识别且不误判() {
        ThingModel model = ThingModelRegistry.loadBuiltin().require("charger-pile-v1");
        FaultInjectionProfile profile = new FaultInjectionProfile(false, 2, 60_000L,
                Set.of(3L), true, 0.0);
        SimulatedChargePileChannel pileChannel = new SimulatedChargePileChannel(
                RESOURCE, model, profile, 20261008L, () -> BASE_MS);
        // 失联档案返回空；此处档案非失联，样点必达
        List<SimulatedTelemetrySample> emitted = pileChannel.generateSamples(8);
        assertFalse(emitted.isEmpty());

        List<MonitoredSample> ingested = monitor.ingestAll(STATION, emitted);
        assertEquals(emitted.size(), ingested.size());

        long duplicates = countMarks(ingested, SampleQualityMark.DUPLICATE);
        long late = countMarks(ingested, SampleQualityMark.LATE);
        long outOfOrder = countMarks(ingested, SampleQualityMark.OUT_OF_ORDER);
        assertTrue(duplicates >= 3, "注入重复必须被识别（按 序号@采样时标 去重）: " + duplicates);
        assertTrue(late >= 4, "注入迟到必须被识别（接收 − 采样 > 30s）: " + late);
        assertTrue(outOfOrder >= 1, "注入乱序必须被识别（采样时标单调性）: " + outOfOrder);

        // 缺失：序号 3 被消费但不产出 → 缺口登记，绝不补 0
        assertFalse(emitted.stream().anyMatch(s -> s.getSeq() == 3), "注入档案：序号 3 不应有样点");
        assertFalse(ingested.stream().anyMatch(s -> s.getSeq() == 3 && s.getPowerKw() == 0.0),
                "缺失不得合成为 0 功率样点");
    }

    @Test
    void 重复副本不作达标证据且曲线保留原样点() {
        DispatchCommand command = ackedCommand("CMD-DUP-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        SimulatedTelemetrySample original = rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 45.0);
        monitor.ingest(STATION, original);
        MonitoredSample duplicate = monitor.ingest(STATION, original);
        assertTrue(duplicate.getQualityMarks().contains(SampleQualityMark.DUPLICATE),
                "同（序号, 采样时标）再次入库必须判重复");

        ExecutionEvaluation evaluation = monitor.evaluate("CMD-DUP-1");
        assertTrue(evaluation.isAttained(), "原样点仍是有效证据");
        assertEquals(2, evaluation.getLink().getSamples().size(), "重复副本保留在曲线中（带标记），不静默丢弃");
        assertEquals(1, evaluation.getLink().evidenceSamples().size(), "重复副本不作达标证据");
    }

    // ---------- 指令之前的样点不作本指令达标证明 ----------

    @Test
    void 指令发送前采样的样点不充当本指令达标证明() {
        DispatchCommand command = ackedCommand("CMD-BEFORE-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS + 10_000);
        // 积压样点：发送前采样（+1s < 发送 +10s），发送后才到达平台
        MonitoredSample backlog = monitor.ingest(STATION,
                rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 11_000, 45.0));
        assertTrue(backlog.isBeforeCommandSent(BASE_MS + 10_000));

        ExecutionEvaluation before = monitor.evaluate("CMD-BEFORE-1");
        assertFalse(before.isAttained(), "指令之前的样点再完美也不能充当本指令达标证明");
        assertNull(before.getDeviation(), "无有效证据不下偏差结论");

        monitor.ingest(STATION, rawSample(RESOURCE, 2, BASE_MS + 11_000, BASE_MS + 12_000, 45.0));
        ExecutionEvaluation after = monitor.evaluate("CMD-BEFORE-1");
        assertTrue(after.isAttained(), "发送后的有效样点才构成到位证据");
    }

    // ---------- 失联进入核查状态 ----------

    @Test
    void 失联无回执无遥测进入核查状态且登记核查告警() {
        channel.offline = true;   // 设备失联：下行无回执
        DispatchCommand command = commandService.issue(new DispatchCommand(
                "CMD-OFFLINE-1", "PLAN-MON-1", 1, RESOURCE, STATION,
                new BigDecimal("75"), new BigDecimal("120"), new BigDecimal("45"), CREATED_AT));
        assertEquals(CommandState.NO_RECEIPT, command.getState());
        monitor.link(EVENT, TENANT, DrDirection.PEAK_SHAVE, command, BASE_MS);

        ExecutionEvaluation evaluation = monitor.evaluate("CMD-OFFLINE-1");
        assertEquals(ExecutionCheckStatus.OFFLINE_INVESTIGATION, evaluation.getCheckStatus(),
                "失联必须进入核查状态");
        assertFalse(evaluation.isAttained(), "无证据不标达标");
        assertEquals(1, evaluation.getAlerts().size());
        assertEquals(MonitorAlertType.OFFLINE_INVESTIGATION, evaluation.getAlerts().get(0).getType());
        assertNull(evaluation.getAlerts().get(0).getEvidence(), "失联无样点证据");
        assertTrue(Double.isNaN(evaluation.getAlerts().get(0).getDeviationKw()), "失联无偏差口径");
    }

    @Test
    void 失联但有遥测回流时达标判定照常基于证据() {
        channel.offline = true;   // 回执丢失
        DispatchCommand command = commandService.issue(new DispatchCommand(
                "CMD-OFFLINE-2", "PLAN-MON-1", 1, RESOURCE, STATION,
                new BigDecimal("75"), new BigDecimal("120"), new BigDecimal("45"), CREATED_AT));
        assertEquals(CommandState.NO_RECEIPT, command.getState());
        monitor.link(EVENT, TENANT, DrDirection.PEAK_SHAVE, command, BASE_MS);
        for (int i = 1; i <= 3; i++) {
            monitor.ingest(STATION, rawSample(RESOURCE, i, BASE_MS + i * 1_000, BASE_MS + i * 1_000, 45.0));
        }
        ExecutionEvaluation evaluation = monitor.evaluate("CMD-OFFLINE-2");
        assertEquals(ExecutionCheckStatus.OFFLINE_INVESTIGATION, evaluation.getCheckStatus(),
                "核查状态与达标判定分列：失联仍须核查");
        assertTrue(evaluation.isAttained(), "设备实际执行了（遥测回流），不得断言未执行");
    }

    // ---------- 回执 ≠ 到位 ≠ 连续达标 三列 ----------

    @Test
    void 回执到位与连续达标三件事分列互不推导() {
        DispatchCommand command = ackedCommand("CMD-THREE-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        // 只有回执、无样点：回执 ≠ 到位
        ExecutionEvaluation receiptOnly = monitor.evaluate("CMD-THREE-1");
        assertTrue(receiptOnly.isReceiptReceived(), "回执环：已受理");
        assertFalse(receiptOnly.isAttained(), "到位环：无遥测证据，受理不等于功率到位");
        assertFalse(receiptOnly.isSteadyAttained());

        // 单点落带：到位 ≠ 连续达标
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 45.0));
        ExecutionEvaluation singlePoint = monitor.evaluate("CMD-THREE-1");
        assertTrue(singlePoint.isAttained());
        assertFalse(singlePoint.isSteadyAttained(), "单点到位不等于连续达标（N=3）");

        monitor.ingest(STATION, rawSample(RESOURCE, 2, BASE_MS + 2_000, BASE_MS + 3_000, 44.0));
        monitor.ingest(STATION, rawSample(RESOURCE, 3, BASE_MS + 3_000, BASE_MS + 4_000, 46.0));
        ExecutionEvaluation steady = monitor.evaluate("CMD-THREE-1");
        assertTrue(steady.isSteadyAttained(), "3 个连续有效样点落带 → 连续达标");
    }

    // ---------- 告警确认留痕 ----------

    @Test
    void 告警确认留痕且幂等首条记录为准() {
        DispatchCommand command = ackedCommand("CMD-CONFIRM-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 60.0));
        monitor.evaluate("CMD-CONFIRM-1");
        MonitorAlert alert = alertRepository.listAll().get(0);
        assertEquals(AlertState.OPEN, alert.getState());

        LocalDateTime confirmedAt = CREATED_AT.plusMinutes(5);
        assertTrue(monitor.confirmAlert(alert.getAlertId(), "operator-01", confirmedAt));
        assertEquals(AlertState.CONFIRMED, alert.getState());
        assertEquals("operator-01", alert.getConfirmedBy());
        assertEquals(confirmedAt, alert.getConfirmedAt());

        // 重复确认幂等：首条记录为准，不被覆盖
        assertFalse(monitor.confirmAlert(alert.getAlertId(), "platform-02", confirmedAt.plusHours(1)));
        assertEquals("operator-01", alert.getConfirmedBy(), "确认人不得被重复确认覆盖");
        assertEquals(confirmedAt, alert.getConfirmedAt());
    }

    @Test
    void 确认后的告警不被后续重评改写也不重复产生() {
        DispatchCommand command = ackedCommand("CMD-CONFIRM-2", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 60.0));
        monitor.evaluate("CMD-CONFIRM-2");
        MonitorAlert alert = alertRepository.listAll().get(0);
        monitor.confirmAlert(alert.getAlertId(), "operator-01", CREATED_AT.plusMinutes(5));

        monitor.ingest(STATION, rawSample(RESOURCE, 2, BASE_MS + 2_000, BASE_MS + 3_000, 70.0));
        monitor.scan();
        assertEquals(1, alertRepository.listAll().size(), "同一指令同方向全生命周期唯一一条告警");
        assertEquals(60.0 - 45.0, alert.getDeviationKw(), 1e-9,
                "确认后证据冻结：更不利的后续样点不再刷新确认过的告警");
        assertEquals(AlertState.CONFIRMED, alert.getState());
    }

    // ---------- 同桩号跨场站隔离 ----------

    @Test
    void 同桩号跨场站样点互不串流不错关联() {
        String otherStation = "cs-station-02";
        DispatchCommand commandA = ackedCommand("CMD-X-A", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        // 同桩号挂到另一场站：填谷目标 100
        DispatchCommand commandB = commandService.issue(new DispatchCommand(
                "CMD-X-B", "PLAN-MON-2", 1, RESOURCE, otherStation,
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("100"), CREATED_AT));
        monitor.link(EVENT, TENANT, DrDirection.VALLEY_FILL, commandB, BASE_MS);

        // B 场站的样点（达标 B 的目标 100）
        for (int i = 1; i <= 3; i++) {
            monitor.ingest(otherStation, rawSample(RESOURCE, i, BASE_MS + i * 1_000, BASE_MS + i * 1_000, 100.0));
        }
        ExecutionEvaluation evaluationA = monitor.evaluate("CMD-X-A");
        assertFalse(evaluationA.isAttained(), "A 场站关联不得吃到 B 场站同桩号的样点");
        assertEquals(0, evaluationA.getLink().getSamples().size());

        ExecutionEvaluation evaluationB = monitor.evaluate("CMD-X-B");
        assertTrue(evaluationB.isAttained());
        assertEquals(3, evaluationB.getLink().getSamples().size());
    }

    // ---------- 缺失不补 0，有效零值与缺失分列 ----------

    @Test
    void 序号缺口登记为缺失有效零值是正常样点() {
        DispatchCommand command = ackedCommand("CMD-GAP-1", STATION, RESOURCE,
                "120", "120", "0", BASE_MS);
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 0.0));
        monitor.ingest(STATION, rawSample(RESOURCE, 2, BASE_MS + 2_000, BASE_MS + 3_000, 0.0));
        monitor.ingest(STATION, rawSample(RESOURCE, 4, BASE_MS + 4_000, BASE_MS + 5_000, 0.0)); // 序号 3 缺失

        CommandMonitorLink link = monitor.requireLink("CMD-GAP-1");
        assertEquals(1, link.getGaps().size(), "序号跳号必须登记缺口");
        SampleGap gap = link.getGaps().get(0);
        assertEquals(3, gap.getFromSeq());
        assertEquals(3, gap.getToSeq());
        assertEquals(1, gap.getMissingCount());

        List<MonitoredSample> samples = link.getSamples();
        assertEquals(3, samples.size(), "缺失不补 0：曲线只有真实样点");
        assertTrue(samples.stream().allMatch(s -> s.getQualityMarks().isEmpty()),
                "真实上报的 0 kW 是有效零值（正常样点），与缺失分列");
        ExecutionEvaluation evaluation = monitor.evaluate("CMD-GAP-1");
        assertTrue(evaluation.isAttained(), "有效零值照常参与达标判定（零目标停机运行）");
    }

    // ---------- 查询与范围 ----------

    @Test
    void 执行查询按事件租户与场站过滤() {
        DispatchCommand command = ackedCommand("CMD-Q-1", STATION, RESOURCE,
                "120", "75", "45", BASE_MS);
        monitor.ingest(STATION, rawSample(RESOURCE, 1, BASE_MS + 1_000, BASE_MS + 2_000, 45.0));

        assertEquals(1, monitor.queryExecutions(EVENT, TENANT, null, null).size());
        assertEquals(0, monitor.queryExecutions("EVT-OTHER", TENANT, null, null).size());
        assertEquals(0, monitor.queryExecutions(EVENT, "tenant-other", null, null).size());
        assertEquals(1, monitor.queryExecutions(EVENT, TENANT, Set.of(STATION), null).size());
        assertEquals(0, monitor.queryExecutions(EVENT, TENANT, Set.of("cs-station-02"), null).size(),
                "场站过滤：别的场站查不到本关联");
        assertEquals(1, monitor.queryExecutions(EVENT, TENANT, null, RESOURCE).size());
        assertEquals(0, monitor.queryExecutions(EVENT, TENANT, null, "pile-other").size());
    }
}
