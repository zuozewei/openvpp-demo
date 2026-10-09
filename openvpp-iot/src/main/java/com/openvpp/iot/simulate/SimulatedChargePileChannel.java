package com.openvpp.iot.simulate;

import com.openvpp.iot.thingmodel.ThingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 模拟充电桩通道 —— 教学实现的充电桩设备侧（本地零依赖）。
 * 专栏第 54~56 篇公共依赖：第 54 篇以本通道完成"模拟设备执行"验证，第 55 篇以
 * 故障注入构造监测与质量验收场景，第 56 篇的评估样点经
 * {@link SimulatedTelemetrySample#toTelemetryPoint()} 同源映射进时序链路。
 *
 * 设备侧职责三件事，对应"接收绝对目标功率 → 回执 → 围绕目标波动上报遥测"：
 *   1) 受理 {@link ChargePileDownlinkChannel#sendTargetPower} 下发的绝对目标功率，
 *      按物模型量程与指令编号规则给出受理/拒绝回执；
 *   2) 维护设备内部状态（当前生效目标），回执 ACCEPTED 即视为设备已按新目标运行；
 *   3) 按采样周期产出围绕当前目标波动的功率样点：来源恒为
 *      {@link TelemetryDataSource#SIMULATED}，采样时标与接收时标分列，
 *      序号单调递增（44260 第 5.4 条数据校核的去重基准）。
 *
 * 装配关系（不破坏已发布的模块边界——gateway 与 iot 互不依赖）：本类不引用
 * openvpp-gateway。app 装配层把样点/回执映射为网关统一报文 DeviceMessage
 * （TELEMETRY / CONTROL_ACK，seq 直传）后进入既有 Consumer&lt;DeviceMessage&gt;
 * 接入管道——复用接入抽象，不另造协议栈。平台侧的期望/上报差异另用 DeviceShadow
 * 记录（desired 记平台期望、reported 追平即闭环），影子属平台侧状态，不在设备内。
 *
 * 确定性：波动由固定种子 Random 产生；采样时标由内部采样钟推导（起步于构造时刻，
 * 按 sampleIntervalMs 步进，缺失序号照走时间）。同一（目标序列、种子、档案、
 * 采样周期）下产出完全可复现，供验收对拍。教学实现非线程安全，单线程驱动。
 */
public class SimulatedChargePileChannel implements ChargePileDownlinkChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedChargePileChannel.class);

    /** 默认采样周期 5s：与 DispatchInstruction 的采样语义同口径 */
    public static final long DEFAULT_SAMPLE_INTERVAL_MS = 5_000L;
    /** 默认波动幅值 ±2 kW：教学设定，让"围绕目标波动"可断言、可复现 */
    public static final double DEFAULT_JITTER_KW = 2.0;
    /** 默认种子：同一资源编号构造的通道默认行为一致（可显式传种子覆盖） */
    public static final long DEFAULT_SEED = 2_026_1008L;

    // ---- 拒绝原因码（回执 reason 字段的机器可读取值） ----
    /** 资源标识与目标设备不符 */
    public static final String REASON_RESOURCE_MISMATCH = "RESOURCE_MISMATCH";
    /** 指令编号重复：设备侧按业务指令编号去重，重试须换新编号 */
    public static final String REASON_DUPLICATE_COMMAND = "DUPLICATE_COMMAND";
    /** 目标值为 NaN / 无穷等非数值形态 */
    public static final String REASON_ILLEGAL_TARGET = "ILLEGAL_TARGET";
    /** 目标值超出物模型量程（含负值：充电功率下界为 0） */
    public static final String REASON_TARGET_OUT_OF_RANGE = "TARGET_OUT_OF_RANGE";

    private final String resourceId;
    private final ThingModel model;
    private final ThingModel.Property powerProperty;
    private final ThingModel.Property targetProperty;
    private final long sampleIntervalMs;
    private final double jitterKw;
    private final LongSupplier clockMs;

    private final Set<String> seenCommandNos = new HashSet<>();
    private final Random rng;

    private volatile FaultInjectionProfile profile;
    private volatile double currentTargetKw;

    private long seqCounter;
    private long nextSampleAtMs;

    /** 便捷构造：默认采样周期 5s、波动 ±2 kW、固定种子、系统时钟 */
    public SimulatedChargePileChannel(String resourceId, ThingModel model,
                                      FaultInjectionProfile profile, long seed,
                                      LongSupplier clockMs) {
        this(resourceId, model, DEFAULT_SAMPLE_INTERVAL_MS, DEFAULT_JITTER_KW,
                profile, seed, clockMs);
    }

    public SimulatedChargePileChannel(String resourceId, ThingModel model,
                                      long sampleIntervalMs, double jitterKw,
                                      FaultInjectionProfile profile, long seed,
                                      LongSupplier clockMs) {
        if (sampleIntervalMs <= 0) {
            throw new IllegalArgumentException("sampleIntervalMs 必须为正: " + sampleIntervalMs);
        }
        if (jitterKw < 0) {
            throw new IllegalArgumentException("jitterKw 不得为负: " + jitterKw);
        }
        this.resourceId = requireText(resourceId, "resourceId");
        this.model = Objects.requireNonNull(model, "model");
        this.powerProperty = model.findProperty("power")
                .orElseThrow(() -> new IllegalArgumentException(
                        "物模型缺少 power 属性（遥测上报点）: " + model.getModelId()));
        this.targetProperty = model.findProperty("targetPower")
                .filter(ThingModel.Property::isWritable)
                .orElseThrow(() -> new IllegalArgumentException(
                        "物模型缺少可写的 targetPower 属性（下发目标点）: " + model.getModelId()));
        this.sampleIntervalMs = sampleIntervalMs;
        this.jitterKw = jitterKw;
        this.profile = profile == null ? FaultInjectionProfile.none() : profile;
        this.rng = new Random(seed);
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.nextSampleAtMs = clockMs.getAsLong();
    }

    /**
     * 下发绝对目标功率（契约见 {@link ChargePileDownlinkChannel}）。
     * 校验顺序即教学演示顺序：失联 → 资源不符 → 编号重复 → 数值非法 → 量程，
     * 任一拒绝都不改变当前生效目标。
     */
    @Override
    public Optional<CommandReceipt> sendTargetPower(String resourceId, BigDecimal targetKw, String commandNo) {
        requireText(resourceId, "resourceId");
        requireText(commandNo, "commandNo");
        Objects.requireNonNull(targetKw, "targetKw");

        if (profile.isOffline()) {
            log.warn("指令 {} → {}：设备失联，无回执（不是拒绝——调用方应超时核查，不得重发）",
                    commandNo, this.resourceId);
            return Optional.empty();
        }
        long ackedAtMs = clockMs.getAsLong();
        if (!this.resourceId.equals(resourceId)) {
            return Optional.of(reject(resourceId, targetKw, commandNo, ackedAtMs,
                    REASON_RESOURCE_MISMATCH));
        }
        // 编号一旦送达本设备（含拒绝）即视为已受理过：重试必须换新编号（契约第 4 条）
        if (!seenCommandNos.add(commandNo)) {
            return Optional.of(reject(resourceId, targetKw, commandNo, ackedAtMs,
                    REASON_DUPLICATE_COMMAND));
        }
        double target = targetKw.doubleValue();
        if (Double.isNaN(target) || Double.isInfinite(target)) {
            return Optional.of(reject(resourceId, targetKw, commandNo, ackedAtMs,
                    REASON_ILLEGAL_TARGET));
        }
        if (!targetProperty.inRange(target)) {
            return Optional.of(reject(resourceId, targetKw, commandNo, ackedAtMs,
                    REASON_TARGET_OUT_OF_RANGE));
        }
        this.currentTargetKw = target;
        log.info("模拟桩 {} 受理目标 {} kW（指令 {}，回执时标 {}）——受理≠到位，到位以遥测达标为准",
                this.resourceId, target, commandNo, ackedAtMs);
        return Optional.of(new CommandReceipt(commandNo, this.resourceId, targetKw,
                CommandReceipt.Status.ACCEPTED, null, ackedAtMs));
    }

    /**
     * 推进 count 个采样周期，返回本窗发出的样点流（按发出顺序，含注入的重复/乱序）。
     * 每次调用推进内部采样钟 count × sampleIntervalMs（缺失序号照走时间）。
     * 失联期间断流：返回空列表（与下行无回执同因，失联判定看档案而非数样点）。
     */
    public List<SimulatedTelemetrySample> generateSamples(int count) {
        if (count <= 0) {
            return List.of();
        }
        if (profile.isOffline()) {
            log.warn("模拟桩 {} 失联中：遥测断流", resourceId);
            return List.of();
        }
        double bias = profile.getBiasKw();
        long lateDelayMs = profile.getLateDelayMs();
        int duplicateEveryN = profile.getDuplicateEveryN();

        List<SimulatedTelemetrySample> naturalOrder = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seq = ++seqCounter;
            long sampledAtMs = nextSampleAtMs;
            nextSampleAtMs += sampleIntervalMs;
            if (profile.getMissingSeqs().contains(seq)) {
                continue;   // 缺失：序号被消费、时间照走，样点不产出（流中出现缺口）
            }
            double jitter = (rng.nextDouble() * 2.0 - 1.0) * jitterKw;
            double powerKw = clampPower(currentTargetKw + bias + jitter);
            EnumSet<TelemetryAnomaly> anomalies = EnumSet.noneOf(TelemetryAnomaly.class);
            if (lateDelayMs > 0) {
                anomalies.add(TelemetryAnomaly.LATE);
            }
            if (bias != 0.0) {
                anomalies.add(TelemetryAnomaly.DIRECTIONAL_BIAS);
            }
            SimulatedTelemetrySample sample = new SimulatedTelemetrySample(
                    resourceId, seq, sampledAtMs, sampledAtMs + lateDelayMs,
                    powerKw, TelemetryDataSource.SIMULATED, anomalies);
            naturalOrder.add(sample);
            if (duplicateEveryN > 0 && seq % duplicateEveryN == 0) {
                // 重复：同资源、同序号、同采样时标、同数值再发一份，显式标记 DUPLICATE
                naturalOrder.add(sample.withAnomaly(TelemetryAnomaly.DUPLICATE));
            }
        }

        List<SimulatedTelemetrySample> emitted = naturalOrder;
        if (profile.isOutOfOrder() && naturalOrder.size() >= 2) {
            // 乱序：相邻两两交换发出顺序（确定性）；被前移的样点（采样时标更晚却先
            // 发出）携带 OUT_OF_ORDER 标记，其采样时标早于发出序列中紧邻的前一条
            emitted = new ArrayList<>(naturalOrder.size());
            for (int i = 0; i < naturalOrder.size(); i += 2) {
                if (i + 1 < naturalOrder.size()) {
                    SimulatedTelemetrySample first = naturalOrder.get(i);
                    SimulatedTelemetrySample second = naturalOrder.get(i + 1);
                    emitted.add(second.withAnomaly(TelemetryAnomaly.OUT_OF_ORDER));
                    emitted.add(first);
                } else {
                    emitted.add(naturalOrder.get(i));
                }
            }
        }
        return List.copyOf(emitted);
    }

    /**
     * 更换故障注入档案。【教学用途】仅供测试与演示切换场景（如失联恢复演示），
     * 生产路径不存在"运行期改故障"的操作。
     */
    public void applyProfile(FaultInjectionProfile profile) {
        this.profile = profile == null ? FaultInjectionProfile.none() : profile;
        log.info("模拟桩 {} 故障注入档案已切换: {}", resourceId, this.profile);
    }

    /** 当前生效目标（kW）；拒绝与失联不改变它 */
    public double currentTargetKw() {
        return currentTargetKw;
    }

    /** 当前故障注入档案 */
    public FaultInjectionProfile currentProfile() {
        return profile;
    }

    public String getResourceId() {
        return resourceId;
    }

    public ThingModel getModel() {
        return model;
    }

    /** 遥测上报点标识（物模型 power 属性，toTelemetryPoint 的映射目标） */
    public String getPowerPropertyIdentifier() {
        return powerProperty.getIdentifier();
    }

    // ---------------- 内部 ----------------

    private CommandReceipt reject(String resourceId, BigDecimal targetKw, String commandNo,
                                  long ackedAtMs, String reason) {
        log.warn("模拟桩 {} 拒绝指令 {}（目标 {} kW）：{}",
                this.resourceId, commandNo, targetKw, reason);
        return new CommandReceipt(commandNo, resourceId, targetKw,
                CommandReceipt.Status.REJECTED, reason, ackedAtMs);
    }

    /** 物理边界截断：功率不得越过物模型量程（如零目标时波动不得打出负功率） */
    private double clampPower(double powerKw) {
        Double min = powerProperty.getMin();
        Double max = powerProperty.getMax();
        if (min != null && powerKw < min) {
            return min;
        }
        if (max != null && powerKw > max) {
            return max;
        }
        return powerKw;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
