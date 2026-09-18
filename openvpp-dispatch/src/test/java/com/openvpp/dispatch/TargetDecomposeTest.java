package com.openvpp.dispatch;

import com.openvpp.dispatch.decompose.CompositeAllocator;
import com.openvpp.dispatch.decompose.DecomposeAlgoType;
import com.openvpp.dispatch.decompose.DecomposeResourceFeature;
import com.openvpp.dispatch.decompose.SlotDistributor;
import com.openvpp.dispatch.decompose.StrategyDraftBuilder;
import com.openvpp.dispatch.decompose.TargetDecomposer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 46 篇目标分解单测：四算法得分 / 复合权重 / 全零均分 / 截断回流守恒 /
 * 末槽漂移 / 槽上限裁剪 / 策略草稿 / 档案兜底。
 */
class TargetDecomposeTest {

    /** 两资源四时隙特征：A 能力大配合一般，B 能力小配合好 */
    private static DecomposeResourceFeature feature(String id, double cap, double coop,
                                                    double rel, double forecast) {
        double[] caps = {cap, cap, cap, cap};
        double[] fc = {forecast, forecast, forecast, forecast};
        double[] price = {0.83, 0.83, 1.20, 1.20};
        return new DecomposeResourceFeature(id, caps, fc, price, rel, coop);
    }

    @Test
    void 比例均衡预测缺失时按能力分摊() {
        // 预测缺失（peReady=false）→ 复合得分退化为只用算法分，能力 2:1 则电量 2:1
        List<DecomposeResourceFeature> features = Arrays.asList(
                featureNoForecast("A", 100),
                featureNoForecast("B", 50));
        CompositeAllocator allocator = new CompositeAllocator();
        List<CompositeAllocator.Allocation> result =
                allocator.allocate(features, DecomposeAlgoType.PROPORTIONAL, 100);
        assertEquals(2.0, result.get(0).targetAdjustKwh() / result.get(1).targetAdjustKwh(),
                0.05, "预测缺失时按能力 2:1 分摊");
        assertFalse(result.get(0).peReady());
    }

    /** 预测缺失的资源特征（slotForecastKw 为空数组）。 */
    private static DecomposeResourceFeature featureNoForecast(String id, double cap) {
        return new DecomposeResourceFeature(id,
                new double[]{cap, cap, cap, cap}, new double[0], new double[0], 0.85, 0.85);
    }

    @Test
    void 可控优先偏向可达率高的资源() {
        List<DecomposeResourceFeature> features = Arrays.asList(
                feature("A", 100, 0.85, 0.60, 300),   // 可达差
                feature("B", 100, 0.85, 0.95, 300));  // 可达好
        CompositeAllocator allocator = new CompositeAllocator();
        List<CompositeAllocator.Allocation> result =
                allocator.allocate(features, DecomposeAlgoType.CONTROLLABILITY, 100);
        assertTrue(result.get(1).targetAdjustKwh() > result.get(0).targetAdjustKwh(),
                "可控优先应给可达率高的资源多分");
    }

    @Test
    void 能力截断后余量回流总量守恒() {
        // A 能力仅 20kWh 但得分高，B 能力 100kWh
        List<DecomposeResourceFeature> features = Arrays.asList(
                feature("A", 20, 0.95, 0.95, 300),
                feature("B", 100, 0.50, 0.50, 100));
        CompositeAllocator allocator = new CompositeAllocator();
        List<CompositeAllocator.Allocation> result =
                allocator.allocate(features, DecomposeAlgoType.COOPERATION, 80);
        double total = result.stream().mapToDouble(CompositeAllocator.Allocation::targetAdjustKwh).sum();
        assertEquals(80.0, total, 0.02, "被截断的预算必须回流，总量严格等于目标");
        assertTrue(result.get(0).targetAdjustKwh() <= 20.01, "A 的分配不得超过自身能力");
    }

    @Test
    void 时隙分发保证电量守恒且不超槽上限() {
        DecomposeResourceFeature f = feature("A", 100, 0.85, 0.85, 300);
        SlotDistributor distributor = new SlotDistributor();
        // 目标 40kWh、4 槽，收益算法下高价时段多担
        double[] kw = distributor.distribute(f, DecomposeAlgoType.BENEFIT, 40);
        double kwh = Arrays.stream(kw).map(v -> v * SlotDistributor.DELTA_HOURS).sum();
        assertEquals(40.0, kwh, 0.02, "ΣkW×Δt 必须严格等于分配电量");
        for (double v : kw) {
            assertTrue(v <= 100.0 + 1e-9, "单槽功率不得超槽上限");
        }
        // 收益算法：高价槽（后两槽价格 1.2）应承担更多
        assertTrue(kw[2] > kw[0], "贵且负荷高的槽应多担");
    }

    @Test
    void 全零特征宁可均分也不抛异常() {
        DecomposeResourceFeature zero = new DecomposeResourceFeature(
                "Z", new double[]{0, 0}, new double[]{0, 0}, new double[0], 0.85, 0.85);
        TargetDecomposer decomposer = new TargetDecomposer();
        TargetDecomposer.DecomposeResult r = decomposer.decompose(
                new TargetDecomposer.DecomposeCommand(
                        java.util.Collections.singletonList(zero), -10,
                        DecomposeAlgoType.PROPORTIONAL));
        assertFalse(r.upRegulation(), "负目标为下调削峰");
        assertEquals(0.0, r.totalAllocatedKwh(), 0.001, "全零能力时预算为 0，不抛异常");
    }

    @Test
    void 档案兜底按方向比例折算() {
        DecomposeResourceFeature down =
                DecomposeResourceFeature.archiveFallback("D", 1000, false, 4);
        assertEquals(300.0, down.slotCapKw()[0], 0.01, "下调按额定 30% 折算");
        DecomposeResourceFeature up =
                DecomposeResourceFeature.archiveFallback("U", 1000, true, 4);
        assertEquals(200.0, up.slotCapKw()[0], 0.01, "上调按额定 20% 折算");
    }

    @Test
    void 策略草稿只含有分量的资源() {
        List<DecomposeResourceFeature> features = Arrays.asList(
                feature("A", 100, 0.85, 0.85, 300),
                feature("B", 0, 0.85, 0.85, 0));   // 零能力：无分量不出草稿
        TargetDecomposer decomposer = new TargetDecomposer();
        TargetDecomposer.DecomposeResult r = decomposer.decompose(
                new TargetDecomposer.DecomposeCommand(features, -50,
                        DecomposeAlgoType.PROPORTIONAL));
        assertEquals(1, r.strategyDraft().size(), "零分量资源不生成策略草稿");
        assertTrue(r.strategyDraft().get(0).resourceId().equals("A"));
        Map<String, double[]> slots = r.slotPowerKw();
        assertTrue(slots.get("A").length == 4, "功率列长度等于槽数");
    }

    @Test
    void 未知算法编码兜底比例均衡() {
        assertEquals(DecomposeAlgoType.PROPORTIONAL, DecomposeAlgoType.fromCode("99"),
                "未知编码兜底比例均衡，前端误传不白屏");
        assertEquals(DecomposeAlgoType.PROPORTIONAL, DecomposeAlgoType.fromCode("comfort"),
                "误传画像偏好也兜底");
    }
}
