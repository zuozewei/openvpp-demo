package com.openvpp.dispatch.decompose;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标分解编排器 —— 专栏第 46 篇主链路：
 * 特征 → 综合分配（资源级电量）→ 时隙分发（15 分钟功率列）→ 策略草稿。
 *
 * 目标电量为带符号 kWh：正=上调、负=下调削峰；分解内部统一用绝对值。
 * 分解只读已落库特征，不实时调用预测/评估服务——分解是重计算，
 * 不能被上游故障连坐。
 */
public class TargetDecomposer {

    private final CompositeAllocator allocator;
    private final SlotDistributor slotDistributor;
    private final StrategyDraftBuilder draftBuilder;

    public TargetDecomposer() {
        this(new CompositeAllocator(), new SlotDistributor(), new StrategyDraftBuilder());
    }

    public TargetDecomposer(CompositeAllocator allocator, SlotDistributor slotDistributor,
                            StrategyDraftBuilder draftBuilder) {
        this.allocator = allocator;
        this.slotDistributor = slotDistributor;
        this.draftBuilder = draftBuilder;
    }

    /**
     * @param command 分解命令（资源特征、时段槽特征、带符号目标、算法）
     * @return 分解结果（资源级分配 + 逐槽功率 + 策略草稿）
     */
    public DecomposeResult decompose(DecomposeCommand command) {
        if (command.slotCount() <= 0) {
            throw new IllegalArgumentException("响应时段至少含一个 15 分钟时隙");
        }
        List<DecomposeResourceFeature> features = command.features();
        if (features.isEmpty()) {
            throw new IllegalArgumentException("分解至少需要一个资源");
        }
        double targetAbs = Math.abs(command.targetKwh());

        // 资源级电量分配
        List<CompositeAllocator.Allocation> allocations =
                allocator.allocate(features, command.algorithm(), targetAbs);

        // 逐资源 15 分钟功率列
        Map<String, DecomposeResourceFeature> byId = new LinkedHashMap<>();
        for (DecomposeResourceFeature f : features) {
            byId.put(f.resourceId(), f);
        }
        Map<String, double[]> slotPower =
                slotDistributor.distributeAll(command.algorithm(), allocations, byId);

        // 策略草稿：AI 出草稿，人拿决定权
        List<StrategyDraftBuilder.Command> draft =
                draftBuilder.build(command.algorithm(), allocations, slotPower);

        return new DecomposeResult(command.targetKwh() > 0, allocations, slotPower, draft);
    }

    /** 分解命令：特征列表 + 带符号目标 + 算法。 */
    public static final class DecomposeCommand {
        private final List<DecomposeResourceFeature> features;
        private final double targetKwh;
        private final DecomposeAlgoType algorithm;

        public DecomposeCommand(List<DecomposeResourceFeature> features, double targetKwh,
                                DecomposeAlgoType algorithm) {
            this.features = new ArrayList<>(features);
            this.targetKwh = targetKwh;
            this.algorithm = algorithm;
        }

        public List<DecomposeResourceFeature> features() {
            return features;
        }

        public double targetKwh() {
            return targetKwh;
        }

        public DecomposeAlgoType algorithm() {
            return algorithm;
        }

        public int slotCount() {
            return features.isEmpty() ? 0 : features.get(0).slotCapKw().length;
        }
    }

    /** 分解结果：资源级分配、逐槽功率、策略草稿。 */
    public static final class DecomposeResult {
        private final boolean upRegulation;
        private final List<CompositeAllocator.Allocation> allocations;
        private final Map<String, double[]> slotPowerKw;
        private final List<StrategyDraftBuilder.Command> strategyDraft;

        DecomposeResult(boolean upRegulation,
                        List<CompositeAllocator.Allocation> allocations,
                        Map<String, double[]> slotPowerKw,
                        List<StrategyDraftBuilder.Command> strategyDraft) {
            this.upRegulation = upRegulation;
            this.allocations = allocations;
            this.slotPowerKw = slotPowerKw;
            this.strategyDraft = strategyDraft;
        }

        public boolean upRegulation() {
            return upRegulation;
        }

        public List<CompositeAllocator.Allocation> allocations() {
            return allocations;
        }

        public Map<String, double[]> slotPowerKw() {
            return slotPowerKw;
        }

        public List<StrategyDraftBuilder.Command> strategyDraft() {
            return strategyDraft;
        }

        /** 分解总量（kWh）：Σ 分配电量，用于守恒校验。 */
        public double totalAllocatedKwh() {
            double s = 0;
            for (CompositeAllocator.Allocation a : allocations) {
                s += a.targetAdjustKwh();
            }
            return s;
        }
    }
}
