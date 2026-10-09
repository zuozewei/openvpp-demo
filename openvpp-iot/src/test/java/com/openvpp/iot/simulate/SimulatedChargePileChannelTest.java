package com.openvpp.iot.simulate;

import com.openvpp.iot.thingmodel.ThingModel;
import com.openvpp.iot.thingmodel.ThingModelRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟充电桩通道单测 —— 对应第 54~56 篇公共依赖的验收口径：
 *   目标接收与回执（接受/拒绝+原因，回执时标）；
 *   遥测围绕目标波动且来源标记 SIMULATED、采样时标与接收时标分列；
 *   重复/迟到/缺失/乱序/方向性偏差注入可复现（同一种子同档案 → 逐字段一致）；
 *   失联场景无回执（Optional.empty()，区别于拒绝）。
 *
 * 时基说明：测试用固定时钟（AtomicLong）与固定种子，时标断言全部基于
 * 内部采样钟（构造时刻起步、5s 步进），与 wall-clock 无关。
 */
class SimulatedChargePileChannelTest {

    private static final String PILE = "pile-001";
    private static final long INTERVAL_MS = 5_000L;
    private static final double JITTER_KW = 2.0;
    private static final long SEED = 42L;

    private static ThingModel chargerModel() {
        return ThingModelRegistry.loadBuiltin().require("charger-pile-v1");
    }

    /** 固定时钟 + 固定种子的健康通道 */
    private static SimulatedChargePileChannel healthyChannel(AtomicLong clock) {
        return new SimulatedChargePileChannel(PILE, chargerModel(), INTERVAL_MS, JITTER_KW,
                FaultInjectionProfile.none(), SEED, clock::get);
    }

    /** 同一（目标、种子、档案）下的完整样点流——可复现性对拍基准 */
    private static List<SimulatedTelemetrySample> streamOf(FaultInjectionProfile profile, long seed) {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = new SimulatedChargePileChannel(PILE, chargerModel(),
                INTERVAL_MS, JITTER_KW, profile, seed, clock::get);
        channel.sendTargetPower(PILE, BigDecimal.valueOf(60.0), "cmd-r");
        return channel.generateSamples(12);
    }

    private static long countSeq(List<SimulatedTelemetrySample> samples, long seq) {
        return samples.stream().filter(s -> s.getSeq() == seq).count();
    }

    // ---------------- 目标接收与回执 ----------------

    @Test
    void 接受合法绝对目标并返回带时标的回执() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SimulatedChargePileChannel channel = healthyChannel(clock);

        Optional<CommandReceipt> receipt =
                channel.sendTargetPower(PILE, BigDecimal.valueOf(60.0), "cmd-001");

        assertTrue(receipt.isPresent(), "健康设备必须给出回执");
        assertEquals(CommandReceipt.Status.ACCEPTED, receipt.get().getStatus());
        assertEquals("cmd-001", receipt.get().getCommandNo());
        assertEquals(PILE, receipt.get().getResourceId());
        assertEquals(0, BigDecimal.valueOf(60.0).compareTo(receipt.get().getTargetKw()),
                "回执须回显绝对目标功率（下发证据链的回执环）");
        assertNull(receipt.get().getReason(), "受理无拒绝原因");
        assertEquals(1_000_000L, receipt.get().getAckedAtMs(), "回执时标 = 设备受理时刻");
        assertEquals(60.0, channel.currentTargetKw(), 1e-9);
    }

    @Test
    void 拒绝超出物模型量程的目标且生效目标不变() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);
        channel.sendTargetPower(PILE, BigDecimal.valueOf(60.0), "cmd-ok");

        Optional<CommandReceipt> rejected =
                channel.sendTargetPower(PILE, BigDecimal.valueOf(150.0), "cmd-over");

        assertTrue(rejected.isPresent());
        assertEquals(CommandReceipt.Status.REJECTED, rejected.get().getStatus());
        assertEquals(SimulatedChargePileChannel.REASON_TARGET_OUT_OF_RANGE,
                rejected.get().getReason());
        assertEquals(60.0, channel.currentTargetKw(), 1e-9, "拒绝不得改变生效目标");
    }

    @Test
    void 拒绝非法数值与越下界目标() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);
        channel.sendTargetPower(PILE, BigDecimal.valueOf(60.0), "cmd-ok");

        // 数值非法：超出 double 可表示范围的 BigDecimal（doubleValue 转为无穷）按非法值拒绝
        Optional<CommandReceipt> infinite = channel.sendTargetPower(
                PILE, new BigDecimal("1E309"), "cmd-inf");
        assertTrue(infinite.isPresent());
        assertEquals(CommandReceipt.Status.REJECTED, infinite.get().getStatus());
        assertEquals(SimulatedChargePileChannel.REASON_ILLEGAL_TARGET, infinite.get().getReason());

        // 充电功率下界为 0：负目标按量程拒绝（物模型 min=0）
        Optional<CommandReceipt> negative = channel.sendTargetPower(
                PILE, BigDecimal.valueOf(-5.0), "cmd-neg");
        assertTrue(negative.isPresent());
        assertEquals(SimulatedChargePileChannel.REASON_TARGET_OUT_OF_RANGE,
                negative.get().getReason());
        assertEquals(60.0, channel.currentTargetKw(), 1e-9, "两次拒绝都不得改变生效目标");
    }

    @Test
    void 设备侧以指令编号去重重复投递() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);
        channel.sendTargetPower(PILE, BigDecimal.valueOf(50.0), "cmd-dup");

        Optional<CommandReceipt> retry =
                channel.sendTargetPower(PILE, BigDecimal.valueOf(55.0), "cmd-dup");

        assertTrue(retry.isPresent());
        assertEquals(CommandReceipt.Status.REJECTED, retry.get().getStatus());
        assertEquals(SimulatedChargePileChannel.REASON_DUPLICATE_COMMAND, retry.get().getReason());
        assertEquals(50.0, channel.currentTargetKw(), 1e-9, "重复编号不得覆盖已生效目标");
    }

    @Test
    void 资源标识不符被拒绝且该指令不计为已送达() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);

        Optional<CommandReceipt> mismatched =
                channel.sendTargetPower("pile-999", BigDecimal.valueOf(40.0), "cmd-x");

        assertTrue(mismatched.isPresent());
        assertEquals(CommandReceipt.Status.REJECTED, mismatched.get().getStatus());
        assertEquals(SimulatedChargePileChannel.REASON_RESOURCE_MISMATCH,
                mismatched.get().getReason());
        assertEquals(0.0, channel.currentTargetKw(), 1e-9);

        // 资源不符的指令不算送达本设备：同一编号换正确资源重投仍被受理
        Optional<CommandReceipt> redelivered =
                channel.sendTargetPower(PILE, BigDecimal.valueOf(40.0), "cmd-x");
        assertTrue(redelivered.isPresent());
        assertEquals(CommandReceipt.Status.ACCEPTED, redelivered.get().getStatus());
    }

    @Test
    void 零目标合法且样点围绕零波动() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);

        Optional<CommandReceipt> receipt =
                channel.sendTargetPower(PILE, BigDecimal.ZERO, "cmd-zero");

        assertTrue(receipt.isPresent());
        assertEquals(CommandReceipt.Status.ACCEPTED, receipt.get().getStatus(),
                "零目标功率合法（停机/回原状），不得因目标为 0 拒绝下发");

        List<SimulatedTelemetrySample> samples = channel.generateSamples(5);
        assertEquals(5, samples.size());
        for (SimulatedTelemetrySample s : samples) {
            assertTrue(s.getPowerKw() >= 0.0 && s.getPowerKw() <= JITTER_KW,
                    "零目标时功率围绕 0 向上波动且物理截断不为负: " + s.getPowerKw());
        }
        // 监测口径（接口契约第 2 条）：零目标按绝对偏差 |实测 − 0| 判定，
        // 本通道保证"零目标样点确实接近零"，"零目标但有实际功率"的异常由注入/真实设备构造
    }

    // ---------------- 遥测形态 ----------------

    @Test
    void 遥测围绕目标波动且来源标记为模拟() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);
        channel.sendTargetPower(PILE, BigDecimal.valueOf(60.0), "cmd-t1");

        List<SimulatedTelemetrySample> samples = channel.generateSamples(10);

        assertEquals(10, samples.size());
        for (int i = 0; i < samples.size(); i++) {
            SimulatedTelemetrySample s = samples.get(i);
            assertEquals(TelemetryDataSource.SIMULATED, s.getSource(),
                    "模拟通道产出的样点必须显式标记 SIMULATED，与测量值/推算值可辨");
            assertTrue(Math.abs(s.getPowerKw() - 60.0) <= JITTER_KW,
                    "样点围绕目标波动: " + s.getPowerKw());
            assertEquals(i + 1, s.getSeq(), "序号单调递增（数据校核去重基准）");
            assertTrue(s.getAnomalies().isEmpty(), "健康设备样点无异常标记");
            if (i > 0) {
                assertEquals(INTERVAL_MS,
                        s.getSampledAtMs() - samples.get(i - 1).getSampledAtMs(),
                        "采样时标按采样周期步进");
            }
        }
        assertEquals(0L, samples.get(0).getSampledAtMs(), "采样钟起步于构造时刻");

        // 入库映射：采样时标进 tsMs，数值与资源标识直传
        SimulatedTelemetrySample first = samples.get(0);
        assertEquals(first.getSampledAtMs(), first.toTelemetryPoint().getTsMs());
        assertEquals(first.getPowerKw(), first.toTelemetryPoint().getValue(), 1e-9);
        assertEquals(PILE, first.toTelemetryPoint().getDeviceId());
        assertEquals("power", first.toTelemetryPoint().getProperty());
    }

    @Test
    void 采样时标与接收时标分列且迟到只动接收时标() {
        // 健康通道：本地回环零传输时延——两字段值相等但语义独立（分列存储，各自成列）
        List<SimulatedTelemetrySample> normal = streamOf(FaultInjectionProfile.none(), SEED);
        for (SimulatedTelemetrySample s : normal) {
            assertEquals(s.getSampledAtMs(), s.getReceivedAtMs(),
                    "无注入时接收时标 = 采样时标（两个字段各自维护）");
        }

        // 迟到注入：接收时标 = 采样时标 + 迟到时延，采样节奏不变
        long lateDelayMs = 30_000L;
        List<SimulatedTelemetrySample> late = streamOf(
                new FaultInjectionProfile(false, 0, lateDelayMs, Set.of(), false, 0.0), SEED);
        assertEquals(normal.size(), late.size());
        for (int i = 0; i < late.size(); i++) {
            SimulatedTelemetrySample s = late.get(i);
            assertEquals(lateDelayMs, s.getReceivedAtMs() - s.getSampledAtMs(),
                    "迟到判定 = 接收时标 − 采样时标");
            assertEquals(normal.get(i).getSampledAtMs(), s.getSampledAtMs(),
                    "迟到不改变采样时刻（采样时标是达标判定与积分的基准）");
            assertTrue(s.getAnomalies().contains(TelemetryAnomaly.LATE));
        }
    }

    // ---------------- 故障注入可复现 ----------------

    @Test
    void 注入重复样点可复现() {
        FaultInjectionProfile profile =
                new FaultInjectionProfile(false, 3, 0L, Set.of(), false, 0.0);
        List<SimulatedTelemetrySample> samples = streamOf(profile, SEED);

        assertEquals(16, samples.size(), "12 个采样周期 + 序号 3/6/9/12 各重复 1 次 = 16");
        assertEquals(2, countSeq(samples, 3), "序号 3 出现两次");
        assertEquals(2, countSeq(samples, 6));
        assertEquals(2, countSeq(samples, 9));
        assertEquals(2, countSeq(samples, 12));
        assertEquals(1, countSeq(samples, 1), "非注入序号不重复");

        List<SimulatedTelemetrySample> dups = samples.stream()
                .filter(s -> s.getSeq() == 3).collect(Collectors.toList());
        assertEquals(dups.get(0).getSampledAtMs(), dups.get(1).getSampledAtMs(),
                "重复样点同序号同采样时标（按（序号,采样时标）去重的考验）");
        assertEquals(dups.get(0).getPowerKw(), dups.get(1).getPowerKw(), "数值一致");
        assertTrue(dups.get(1).getAnomalies().contains(TelemetryAnomaly.DUPLICATE),
                "后到的副本携带 DUPLICATE 标记");
        assertTrue(dups.get(0).getAnomalies().isEmpty(), "原样点不标异常");

        assertEquals(streamOf(profile, SEED), samples, "同一种子同档案 → 样点流逐字段一致");
    }

    @Test
    void 注入缺失样点可复现且时间照走() {
        FaultInjectionProfile profile =
                new FaultInjectionProfile(false, 0, 0L, Set.of(2L, 5L), false, 0.0);
        List<SimulatedTelemetrySample> samples = streamOf(profile, SEED);

        assertEquals(10, samples.size(), "12 个采样周期 − 缺失 2 个 = 10");
        Set<Long> presentSeqs = samples.stream()
                .map(SimulatedTelemetrySample::getSeq).collect(Collectors.toSet());
        assertFalse(presentSeqs.contains(2L), "序号 2 缺失（不补 0，直接缺席）");
        assertFalse(presentSeqs.contains(5L), "序号 5 缺失");
        assertTrue(presentSeqs.contains(1L) && presentSeqs.contains(3L) && presentSeqs.contains(6L));

        // 缺失序号照走时间：序号 3 的采样时刻 = 2 个周期之后（缺口在时间上真实存在）
        SimulatedTelemetrySample seq3 = samples.stream()
                .filter(s -> s.getSeq() == 3).findFirst().orElseThrow();
        assertEquals(2 * INTERVAL_MS, seq3.getSampledAtMs(),
                "缺失消耗采样序号与时间，流中出现真实缺口");

        assertEquals(streamOf(profile, SEED), samples, "同一种子同档案 → 样点流逐字段一致");
    }

    @Test
    void 注入乱序样点可复现且样点自身时标不变() {
        FaultInjectionProfile profile =
                new FaultInjectionProfile(false, 0, 0L, Set.of(), true, 0.0);
        List<SimulatedTelemetrySample> samples = streamOf(profile, SEED);

        // 发出流中存在相邻 inversion：某样点的采样时标晚于发出序列中紧邻的前一条
        boolean hasInversion = false;
        for (int i = 0; i + 1 < samples.size(); i++) {
            if (samples.get(i).getSampledAtMs() > samples.get(i + 1).getSampledAtMs()) {
                hasInversion = true;
                assertTrue(samples.get(i).getAnomalies().contains(TelemetryAnomaly.OUT_OF_ORDER),
                        "被前移的样点（采样时标更晚却先发出）携带 OUT_OF_ORDER 标记");
            }
        }
        assertTrue(hasInversion, "乱序注入必须在发出流中产生逆序");

        Set<Long> sortedSeqs = samples.stream().map(SimulatedTelemetrySample::getSeq)
                .collect(Collectors.toSet());
        assertEquals(12, sortedSeqs.size(), "乱序不丢样点：12 个序号齐全");

        assertEquals(streamOf(profile, SEED), samples, "同一种子同档案 → 样点流逐字段一致");
    }

    @Test
    void 注入方向性偏差可复现且全样点系统性偏低() {
        double biasKw = -10.0;
        FaultInjectionProfile profile =
                new FaultInjectionProfile(false, 0, 0L, Set.of(), false, biasKw);
        List<SimulatedTelemetrySample> samples = streamOf(profile, SEED);

        for (SimulatedTelemetrySample s : samples) {
            assertTrue(s.getPowerKw() <= 60.0 + biasKw + JITTER_KW,
                    "系统性偏低：样点不高于 目标+偏差+波动 上界");
            assertTrue(s.getPowerKw() >= 60.0 + biasKw - JITTER_KW,
                    "波动幅值不变：样点不低于 目标+偏差−波动 下界");
            assertTrue(s.getPowerKw() < 60.0, "每个样点都低于目标（方向性超差）");
            assertTrue(s.getAnomalies().contains(TelemetryAnomaly.DIRECTIONAL_BIAS));
        }
        double mean = samples.stream().mapToDouble(SimulatedTelemetrySample::getPowerKw)
                .average().orElseThrow();
        assertTrue(mean <= 60.0 + biasKw + JITTER_KW,
                "均值同样系统性偏低，削峰告警方向可判：mean=" + mean);

        assertEquals(streamOf(profile, SEED), samples, "同一种子同档案 → 样点流逐字段一致");
    }

    // ---------------- 失联 ----------------

    @Test
    void 失联无回执且断流恢复后可继续() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = new SimulatedChargePileChannel(PILE, chargerModel(),
                INTERVAL_MS, JITTER_KW,
                new FaultInjectionProfile(true, 0, 0L, Set.of(), false, 0.0), SEED, clock::get);

        Optional<CommandReceipt> noAck =
                channel.sendTargetPower(PILE, BigDecimal.valueOf(60.0), "cmd-off");
        assertFalse(noAck.isPresent(), "失联 = 无回执（Optional.empty），不是 REJECTED");
        assertEquals(0.0, channel.currentTargetKw(), 1e-9, "失联时指令未到设备，目标不变");
        assertTrue(channel.generateSamples(5).isEmpty(), "失联期间遥测断流");

        // 失联恢复（教学演示场景切换）：下行回执与遥测同时恢复
        channel.applyProfile(FaultInjectionProfile.none());
        Optional<CommandReceipt> recovered =
                channel.sendTargetPower(PILE, BigDecimal.valueOf(40.0), "cmd-on");
        assertTrue(recovered.isPresent());
        assertEquals(CommandReceipt.Status.ACCEPTED, recovered.get().getStatus());

        List<SimulatedTelemetrySample> samples = channel.generateSamples(3);
        assertEquals(3, samples.size());
        for (SimulatedTelemetrySample s : samples) {
            assertTrue(Math.abs(s.getPowerKw() - 40.0) <= JITTER_KW,
                    "恢复后样点围绕新目标波动: " + s.getPowerKw());
        }
    }

    @Test
    void 回执与遥测达标分离受理不证明到位() {
        AtomicLong clock = new AtomicLong(0L);
        SimulatedChargePileChannel channel = healthyChannel(clock);
        CommandReceipt receipt = channel.sendTargetPower(
                PILE, BigDecimal.valueOf(60.0), "cmd-sep").orElseThrow();

        // 受理瞬间不产生任何遥测：ACK 只证明受理，到位必须由后续样点围绕目标的判定证明
        assertTrue(channel.generateSamples(0).isEmpty());

        // 回执时标取受理时刻的系统时钟；采样时标走内部采样钟（起步于构造时刻、按周期
        // 步进）——两者分属两个时间域，受理后 wall-clock 前进不改变采样时标
        clock.set(77_000L);
        List<SimulatedTelemetrySample> samples = channel.generateSamples(2);
        assertEquals(2, samples.size());
        assertEquals(0L, samples.get(0).getSampledAtMs(), "采样钟独立步进，不受受理后的时钟影响");
        assertEquals(INTERVAL_MS, samples.get(1).getSampledAtMs() - samples.get(0).getSampledAtMs());
        assertEquals(0L, receipt.getAckedAtMs(), "回执时标 = 受理时刻（当时的系统时钟）");
    }
}
