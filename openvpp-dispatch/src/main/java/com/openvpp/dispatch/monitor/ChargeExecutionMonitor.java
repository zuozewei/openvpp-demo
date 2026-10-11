package com.openvpp.dispatch.monitor;

import com.openvpp.dispatch.command.CommandState;
import com.openvpp.dispatch.command.DispatchCommand;
import com.openvpp.iot.simulate.SimulatedTelemetrySample;
import com.openvpp.market.charge.DrDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 充电执行监测 —— 指令下发后的遥测回流、质量判定、方向性告警与达标评估
 * （第 55 篇底座，3.5.1 业务链路"模拟设备执行与遥测"的监测侧）。
 *
 * 职责四件，对应"监测与质量"验收格：
 * <ol>
 *   <li>遥测入库与指令关联：样点带资源/指令关联、双时标、实际功率、来源
 *       （SIMULATED 显式标记）、质量标记；关联按（场站, 资源）双键 —— 同桩号
 *       跨场站互不串流；</li>
 *   <li>质量判定（独立识别，不信注入方自证）：重复按（序号, 采样时标）去重、
 *       迟到按接收−采样超阈值、乱序按采样时标单调性、缺失按序号断档记
 *       {@link SampleGap} —— 缺失不补 0，有效零值是正常样点；</li>
 *   <li>达标判定三件事分列：回执 ≠ 到位 ≠ 连续达标；达标只基于指令发送后的
 *       有效样点（指令之前的样点、重复副本、迟到样点不作证据）；</li>
 *   <li>方向性偏差与告警：削峰判"压不下来"（实测 &gt; 目标 + 容差）、填谷判
 *       "抬不上去"（实测 &lt; 目标 − 容差），零目标用绝对偏差
 *       （比例偏差除零无定义，会掩盖"零目标但有实际功率"）；失联（无回执无遥测）
 *       进入核查状态并登记告警 —— 不直接断言设备未执行。</li>
 * </ol>
 *
 * 评估口径：{@link #evaluate} 与 {@link #scan} 幂等 —— 告警按
 * （指令, 类型, 方向）去重，OPEN 期间取更不利样点刷新证据，人工确认后冻结；
 * 重复扫描不产生重复告警。教学实现为单 JVM 内存版（synchronized 串行化）；
 * 生产形态样点/告警落库（时序库 + 告警表），判定口径不变。
 */
public class ChargeExecutionMonitor {

    private static final Logger log = LoggerFactory.getLogger(ChargeExecutionMonitor.class);

    /** 达标/超差容差（kW） */
    private final double toleranceKw;
    /** 迟到阈值（ms）：接收时标 − 采样时标超过即判迟到 */
    private final long lateThresholdMs;
    /** 连续达标所需的有效样点数 */
    private final int steadySampleCount;
    private final MonitorAlertRepository alertRepository;

    /** 全部关联（按指令编号，历史关联留存可查） */
    private final Map<String, CommandMonitorLink> linksByCommandNo = new LinkedHashMap<>();
    /** （场站, 资源）→ 当前生效关联：新指令下发即顶替，样点只进生效关联 */
    private final Map<String, CommandMonitorLink> activeByPile = new LinkedHashMap<>();
    /** （场站, 资源）→ 样点流状态：序号水位与去重基准（设备会话级，同桩号跨场站各自独立；
     *  会话边界 = 监测关联建立，见 {@link #link}） */
    private final Map<String, StreamState> streams = new LinkedHashMap<>();
    /** 告警去重索引：（指令, 类型, 方向）→ 告警（全生命周期唯一一条） */
    private final Map<String, MonitorAlert> alertsByKey = new LinkedHashMap<>();

    public ChargeExecutionMonitor(double toleranceKw, long lateThresholdMs,
                                  int steadySampleCount, MonitorAlertRepository alertRepository) {
        if (toleranceKw < 0) {
            throw new IllegalArgumentException("容差不得为负: " + toleranceKw);
        }
        if (lateThresholdMs < 0) {
            throw new IllegalArgumentException("迟到阈值不得为负: " + lateThresholdMs);
        }
        if (steadySampleCount < 1) {
            throw new IllegalArgumentException("连续达标样点数必须 ≥ 1: " + steadySampleCount);
        }
        this.toleranceKw = toleranceKw;
        this.lateThresholdMs = lateThresholdMs;
        this.steadySampleCount = steadySampleCount;
        this.alertRepository = Objects.requireNonNull(alertRepository, "告警仓库不能为空");
    }

    /**
     * 建立指令监测关联：指令发送后此（场站, 资源）到达的样点进入本关联。
     *
     * 同指令重复关联幂等返回；新指令顶替同（场站, 资源）旧关联（旧关联留存，
     * 不再进新样点）。
     *
     * <p>会话口径：新关联即新设备会话 —— 样点流状态（序号水位、去重基准）随之重置。
     * 演示编排每次运行自造全新模拟设备（序号自 1 重启、采样钟重新起步），跨运行
     * 延续水位会把新样点误判为乱序/吞掉缺口；真实接入以设备连接会话为会话边界，
     * 教学实现以监测关联建立为边界，口径一致。</p>
     *
     * @param sentAtMs 指令发送时刻（epoch ms，必须与样点同钟）—— 达标证据的时界
     */
    public synchronized CommandMonitorLink link(String eventId, String organizerTenantId,
                                                DrDirection direction, DispatchCommand command,
                                                long sentAtMs) {
        Objects.requireNonNull(command, "指令不能为空");
        CommandMonitorLink existing = linksByCommandNo.get(command.getCommandNo());
        if (existing != null) {
            return existing;
        }
        CommandMonitorLink link = new CommandMonitorLink(eventId, organizerTenantId,
                direction, command, sentAtMs);
        linksByCommandNo.put(command.getCommandNo(), link);
        String pileKey = pileKey(link.getCommand().getStationId(), link.getCommand().getResourceId());
        activeByPile.put(pileKey, link);
        streams.put(pileKey, new StreamState());
        log.info("监测关联建立: 指令 {} → {}@{}（事件 {}，方向 {}，发送时界 {} ms）",
                command.getCommandNo(), link.getCommand().getResourceId(),
                link.getCommand().getStationId(), eventId, direction, sentAtMs);
        return link;
    }

    /**
     * 遥测样点入库：质量判定（独立识别）+ 指令关联 + 缺口登记。
     *
     * @param stationId 样点归属场站（接入侧声明，平台知道样点从哪个场站进来；
     *                  与资源标识组成双键，同桩号跨场站互不串流）
     * @param sample    采集样点（iot 模拟通道/真实接入链路产出）
     * @return 入库后的监测样点（质量标记与关联已定型）
     */
    public synchronized MonitoredSample ingest(String stationId, SimulatedTelemetrySample sample) {
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("样点归属场站不能为空");
        }
        Objects.requireNonNull(sample, "样点不能为空");
        String resourceId = sample.getResourceId();
        String pileKey = pileKey(stationId, resourceId);
        StreamState stream = streams.computeIfAbsent(pileKey, key -> new StreamState());

        EnumSet<SampleQualityMark> marks = EnumSet.noneOf(SampleQualityMark.class);
        String duplicateKey = sample.getSeq() + "@" + sample.getSampledAtMs();
        boolean duplicate = !stream.seenSamples.add(duplicateKey);
        if (duplicate) {
            marks.add(SampleQualityMark.DUPLICATE);
        }
        // 迟到独立判定：接收 − 采样超阈值（不采信注入方标记）
        if (sample.getReceivedAtMs() - sample.getSampledAtMs() > lateThresholdMs) {
            marks.add(SampleQualityMark.LATE);
        }
        // 乱序独立判定：采样时标早于本流已见最大采样时标（重复副本除外）
        if (!duplicate && stream.maxSampledAtMs != Long.MIN_VALUE
                && sample.getSampledAtMs() < stream.maxSampledAtMs) {
            marks.add(SampleQualityMark.OUT_OF_ORDER);
        }
        // 缺口登记：序号跳号即缺失（重复副本不推进序号水位，也不记缺口）
        if (!duplicate) {
            long expectedSeq = stream.lastSeq + 1;
            if (sample.getSeq() > expectedSeq) {
                SampleGap gap = new SampleGap(stationId, resourceId,
                        expectedSeq, sample.getSeq() - 1,
                        stream.lastSampledAtMs, sample.getSampledAtMs());
                CommandMonitorLink active = activeByPile.get(pileKey);
                if (active != null) {
                    active.addGap(gap);
                } else {
                    stream.unassociatedGaps.add(gap);
                }
                log.warn("遥测缺口: {}@{} 序号 {}..{} 缺失（不补 0，曲线留缺）",
                        resourceId, stationId, gap.getFromSeq(), gap.getToSeq());
            }
            if (sample.getSeq() > stream.lastSeq) {
                stream.lastSeq = sample.getSeq();
                stream.lastSampledAtMs = sample.getSampledAtMs();
            }
            stream.maxSampledAtMs = Math.max(stream.maxSampledAtMs, sample.getSampledAtMs());
        }

        CommandMonitorLink active = activeByPile.get(pileKey);
        MonitoredSample monitored = new MonitoredSample(stationId, resourceId,
                sample.getSeq(), sample.getSampledAtMs(), sample.getReceivedAtMs(),
                sample.getPowerKw(), sample.getSource(), marks,
                active == null ? null : active.getCommand().getCommandNo());
        if (active != null) {
            active.addSample(monitored);
        } else {
            stream.unassociatedSamples.add(monitored);
        }
        return monitored;
    }

    /** 便捷重载：批量入库（按传入顺序逐条判定） */
    public synchronized List<MonitoredSample> ingestAll(String stationId,
                                                        List<SimulatedTelemetrySample> samples) {
        List<MonitoredSample> ingested = new ArrayList<>();
        if (samples == null) {
            return ingested;
        }
        for (SimulatedTelemetrySample sample : samples) {
            ingested.add(ingest(stationId, sample));
        }
        return List.copyOf(ingested);
    }

    /**
     * 评估单条指令：回执/到位/连续达标三列 + 核查状态 + 方向性偏差，并同步告警。
     * 幂等：重复评估不产生重复告警。
     */
    public synchronized ExecutionEvaluation evaluate(String commandNo) {
        CommandMonitorLink link = linksByCommandNo.get(commandNo);
        if (link == null) {
            throw new IllegalArgumentException("未建立监测关联的指令: " + commandNo);
        }
        return evaluate(link, true);
    }

    /** 全量扫描评估（查询入口的"查询即扫描"语义；幂等，可反复调用） */
    public synchronized List<ExecutionEvaluation> scan() {
        List<ExecutionEvaluation> evaluations = new ArrayList<>();
        for (CommandMonitorLink link : linksByCommandNo.values()) {
            evaluations.add(evaluate(link, true));
        }
        return List.copyOf(evaluations);
    }

    /**
     * 执行查询：按事件/租户/场站/资源过滤的评估视图（评估为纯计算，不同步告警；
     * 告警由 {@link #scan} 统一同步）。
     */
    public synchronized List<ExecutionEvaluation> queryExecutions(String eventId,
                                                                  String organizerTenantId,
                                                                  Set<String> stationIds,
                                                                  String resourceId) {
        if (organizerTenantId == null || organizerTenantId.isBlank()) {
            throw new IllegalArgumentException("组织方租户不能为空");
        }
        List<ExecutionEvaluation> matched = new ArrayList<>();
        for (CommandMonitorLink link : linksByCommandNo.values()) {
            if (!link.getOrganizerTenantId().equals(organizerTenantId)) {
                continue;
            }
            if (eventId != null && !eventId.isBlank() && !link.getEventId().equals(eventId)) {
                continue;
            }
            if (stationIds != null && !stationIds.contains(link.getCommand().getStationId())) {
                continue;
            }
            if (resourceId != null && !resourceId.isBlank()
                    && !link.getCommand().getResourceId().equals(resourceId)) {
                continue;
            }
            matched.add(evaluate(link, false));
        }
        return List.copyOf(matched);
    }

    /**
     * 告警人工确认：OPEN → CONFIRMED，记录确认人/时间（审计证据）。
     *
     * @return true=本次完成状态迁移；false=已确认过，幂等返回（首条记录为准）
     */
    public synchronized boolean confirmAlert(String alertId, String confirmer, LocalDateTime at) {
        MonitorAlert alert = alertRepository.require(alertId);
        boolean transitioned = alert.confirm(confirmer, at);
        log.info("告警确认: {}（{} {}） transitioned={} 确认人 {} 时刻 {}",
                alert.getAlertId(), alert.getType(), alert.getDirection(),
                transitioned, alert.getConfirmedBy(), alert.getConfirmedAt());
        return transitioned;
    }

    /** 告警条件查询（租户必填；场站 null=本租户全部，空集合=明确无可见数据短路为空） */
    public synchronized List<MonitorAlert> listAlerts(String eventId, String organizerTenantId,
                                                      Set<String> stationIds, AlertState state) {
        if (stationIds != null && stationIds.isEmpty()) {
            return List.of();
        }
        return alertRepository.list(eventId, organizerTenantId, stationIds, state);
    }

    public synchronized CommandMonitorLink requireLink(String commandNo) {
        CommandMonitorLink link = linksByCommandNo.get(commandNo);
        if (link == null) {
            throw new IllegalArgumentException("未建立监测关联的指令: " + commandNo);
        }
        return link;
    }

    public double getToleranceKw() {
        return toleranceKw;
    }

    public long getLateThresholdMs() {
        return lateThresholdMs;
    }

    public int getSteadySampleCount() {
        return steadySampleCount;
    }

    // ---------------- 内部 ----------------

    private ExecutionEvaluation evaluate(CommandMonitorLink link, boolean syncAlerts) {
        DispatchCommand command = link.getCommand();
        boolean receiptReceived = command.getReceiptAckedAtMs() != null;

        // 指令被拒：目标未生效，监测不下达标/偏差结论（回执环照常记录）
        if (command.getState() == CommandState.REJECTED) {
            return new ExecutionEvaluation(link, receiptReceived, false, false,
                    ExecutionCheckStatus.MONITORING, null, alertsOf(command.getCommandNo()));
        }

        List<MonitoredSample> evidence = link.evidenceSamples();
        double targetKw = command.getTargetPowerKw().doubleValue();
        boolean attained = false;
        int bestRun = 0;
        int currentRun = 0;
        List<MonitoredSample> ordered = new ArrayList<>(evidence);
        ordered.sort(Comparator.comparingLong(MonitoredSample::getSampledAtMs));
        for (MonitoredSample sample : ordered) {
            if (Math.abs(sample.getPowerKw() - targetKw) <= toleranceKw) {
                attained = true;
                currentRun++;
                bestRun = Math.max(bestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        boolean steadyAttained = bestRun >= steadySampleCount;

        // 方向性偏差：取有效证据里最不利样点（削峰看正偏差、填谷看负偏差、零目标看绝对值）
        boolean zeroTarget = command.getTargetPowerKw().signum() == 0;
        DirectionalDeviation deviation = null;
        MonitoredSample worstSample = null;
        double worstExcess = Double.NEGATIVE_INFINITY;
        double worstDeviationKw = 0;
        for (MonitoredSample sample : evidence) {
            double deviationKw = sample.getPowerKw() - targetKw;
            double excess;
            if (zeroTarget) {
                excess = Math.abs(deviationKw);
            } else if (link.getDirection() == DrDirection.PEAK_SHAVE) {
                excess = deviationKw;
            } else {
                excess = -deviationKw;
            }
            if (excess > worstExcess) {
                worstExcess = excess;
                worstDeviationKw = deviationKw;
                worstSample = sample;
            }
        }
        if (!evidence.isEmpty()) {
            DeviationBasis basis = zeroTarget
                    ? DeviationBasis.ABSOLUTE_ZERO_TARGET
                    : DeviationBasis.DIRECTIONAL;
            deviation = new DirectionalDeviation(link.getDirection(), worstDeviationKw,
                    toleranceKw, basis, worstExcess > toleranceKw);
        }

        ExecutionCheckStatus checkStatus = command.getState() == CommandState.NO_RECEIPT
                ? ExecutionCheckStatus.OFFLINE_INVESTIGATION
                : ExecutionCheckStatus.MONITORING;

        if (syncAlerts) {
            syncAlerts(link, deviation, worstSample, worstExcess);
        }
        return new ExecutionEvaluation(link, receiptReceived, attained, steadyAttained,
                checkStatus, deviation, alertsOf(command.getCommandNo()));
    }

    private void syncAlerts(CommandMonitorLink link, DirectionalDeviation deviation,
                            MonitoredSample worstSample, double worstExcess) {
        DispatchCommand command = link.getCommand();
        if (command.getState() == CommandState.NO_RECEIPT && link.getSamples().isEmpty()) {
            // 失联：无回执无遥测 → 核查告警（不是"设备未执行"的断言）
            ensureAlert(link, MonitorAlertType.OFFLINE_INVESTIGATION, null,
                    Double.NaN, 0, null, Double.NaN);
            return;
        }
        if (command.getState() == CommandState.ACKED && deviation != null
                && deviation.isExceeded() && worstSample != null) {
            AlertEvidence evidence = new AlertEvidence(worstSample.getSampledAtMs(),
                    worstSample.getReceivedAtMs(), worstSample.getPowerKw());
            ensureAlert(link, MonitorAlertType.DIRECTIONAL_DEVIATION, deviation.getBasis(),
                    deviation.getDeviationKw(), worstExcess, evidence, deviation.getToleranceKw());
        }
    }

    /**
     * 告警去重登记：（指令, 类型, 方向）全生命周期唯一一条 —— OPEN 期间以更不利
     * 判定刷新偏差与证据，确认后冻结不重复告警（教学口径：人工确认为告警收口）。
     */
    private void ensureAlert(CommandMonitorLink link, MonitorAlertType type,
                             DeviationBasis basis, double deviationKw, double excessKw,
                             AlertEvidence evidence, double alertToleranceKw) {
        String key = link.getCommand().getCommandNo() + "|" + type + "|" + link.getDirection();
        MonitorAlert existing = alertsByKey.get(key);
        if (existing != null) {
            existing.refreshIfWorse(deviationKw, excessKw, evidence);
            return;
        }
        MonitorAlert alert = new MonitorAlert("ALT-" + UUID.randomUUID(), type,
                link.getEventId(), link.getOrganizerTenantId(),
                link.getCommand().getStationId(), link.getCommand().getResourceId(),
                link.getCommand().getCommandNo(), link.getDirection(), basis,
                deviationKw, excessKw, evidence, alertToleranceKw, LocalDateTime.now());
        alertsByKey.put(key, alert);
        alertRepository.save(alert);
        log.warn("监测告警产生: {} {} {}@{} 指令 {} 偏差 {} kW（容差 {} kW，证据 {}）",
                alert.getAlertId(), type, link.getDirection(),
                link.getCommand().getResourceId(), link.getCommand().getCommandNo(),
                deviationKw, alertToleranceKw, evidence);
    }

    private List<MonitorAlert> alertsOf(String commandNo) {
        List<MonitorAlert> matched = new ArrayList<>();
        for (MonitorAlert alert : alertRepository.listAll()) {
            if (alert.getCommandNo().equals(commandNo)) {
                matched.add(alert);
            }
        }
        return matched;
    }

    private static String pileKey(String stationId, String resourceId) {
        return stationId + "" + resourceId;
    }

    /** 样点流状态：序号水位、去重基准与无关联期间的留存（跨关联延续） */
    private static final class StreamState {
        /** 已见最大序号（0 = 尚未见样点） */
        private long lastSeq;
        /** 最后一条按序号推进的实样采样时标（缺口登记的左边界；-1 = 流首） */
        private long lastSampledAtMs = -1;
        /** 已见最大采样时标（乱序判定基准） */
        private long maxSampledAtMs = Long.MIN_VALUE;
        /** 去重基准：已见（序号@采样时标） */
        private final Set<String> seenSamples = new HashSet<>();
        /** 无生效指令期间的样点与缺口（留存可查，不进任何指令证据） */
        private final List<MonitoredSample> unassociatedSamples = new ArrayList<>();
        private final List<SampleGap> unassociatedGaps = new ArrayList<>();
    }
}
