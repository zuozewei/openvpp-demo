package com.openvpp.dispatch.decompose;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 综合分配器 —— 专栏第 46 篇五步流水线：
 * 综合得分 → 初分配 → 能力截断 → 余量回流 → 末位抹平。
 *
 * 复合得分 = 0.30×预测分 + 0.70×算法分（产品约定常量，非运行时算出）；
 * 预测不可用（peReady=false）的资源只用算法分。
 * 余量回流最多 8 轮：得分高的大资源可能能力不够，被截掉的预算必须
 * 流向还有余量的资源，否则总量对不上账。
 */
public class CompositeAllocator {

    /** 预测分权重（产品约定，权重和 ≤ 0 时回退设计默认防除零） */
    public static final double DEFAULT_WEIGHT_PE = 0.30;
    public static final double DEFAULT_WEIGHT_ALGO = 0.70;
    /** 余量回流轮数上限（正常两三轮收敛，8 轮是防极端数据的保险丝） */
    public static final int REDISTRIBUTE_ROUNDS = 8;

    private final double weightPe;
    private final double weightAlgo;

    public CompositeAllocator() {
        this(DEFAULT_WEIGHT_PE, DEFAULT_WEIGHT_ALGO);
    }

    public CompositeAllocator(double weightPe, double weightAlgo) {
        double sum = weightPe + weightAlgo;
        if (sum <= 0) {
            this.weightPe = DEFAULT_WEIGHT_PE;
            this.weightAlgo = DEFAULT_WEIGHT_ALGO;
        } else {
            this.weightPe = weightPe / sum;
            this.weightAlgo = weightAlgo / sum;
        }
    }

    /**
     * @param features  资源特征（顺序即输出顺序）
     * @param algoType  分配算法
     * @param targetKwh 目标电量绝对值（带符号目标由调用方取绝对值）
     * @return 每资源的分配电量（kWh，Σ 分配 = min(目标, Σ 能力)，严格守恒）
     */
    public List<Allocation> allocate(List<DecomposeResourceFeature> features,
                                     DecomposeAlgoType algoType, double targetKwh) {
        Map<String, Double> peRaw = new LinkedHashMap<>();
        Map<String, Double> algoRaw = new LinkedHashMap<>();
        Map<String, Double> capacity = new LinkedHashMap<>();
        for (DecomposeResourceFeature f : features) {
            // 预测分：默认口径 Σ|预测功率|×Δt（预测缺失记 0，参与但不得分）
            double pe = 0;
            for (double p : f.slotForecastKw()) {
                pe += Math.abs(p) * 0.25;
            }
            peRaw.put(f.resourceId(), pe);
            algoRaw.put(f.resourceId(), algoScore(algoType, f));
            capacity.put(f.resourceId(), f.capacityKwh());
        }
        Map<String, Double> pe = ScoreNormalizer.normalize(peRaw);
        Map<String, Double> algo = ScoreNormalizer.normalize(algoRaw);

        Map<String, Double> composite = new LinkedHashMap<>();
        Map<String, Boolean> peReady = new LinkedHashMap<>();
        for (DecomposeResourceFeature f : features) {
            String id = f.resourceId();
            double s = f.peReady() ? weightPe * pe.get(id) + weightAlgo * algo.get(id)
                    : algo.get(id);
            composite.put(id, s);
            peReady.put(id, f.peReady());
        }
        Map<String, Double> tilde = ScoreNormalizer.normalize(composite);

        double budget = Math.min(targetKwh, sum(capacity));

        // 初分配 + 能力截断
        Map<String, Double> allocated = new LinkedHashMap<>();
        for (String id : tilde.keySet()) {
            allocated.put(id, Math.min(budget * tilde.get(id), capacity.get(id)));
        }
        // 余量回流（最多 8 轮，剩余 ≤ 0.0001 提前结束）
        for (int round = 0; round < REDISTRIBUTE_ROUNDS; round++) {
            double remain = budget - sum(allocated);
            if (remain <= 0.0001) {
                break;
            }
            double tildeRoom = 0;
            for (String id : allocated.keySet()) {
                if (capacity.get(id) - allocated.get(id) > 0.0001) {
                    tildeRoom += tilde.get(id);
                }
            }
            if (tildeRoom <= 0) {
                break;
            }
            for (String id : allocated.keySet()) {
                double left = capacity.get(id) - allocated.get(id);
                if (left > 0.0001) {
                    allocated.put(id, allocated.get(id)
                            + Math.min(remain * tilde.get(id) / tildeRoom, left));
                }
            }
        }
        // 末位抹平：从最后一个资源倒序补浮点漂移（不超能力、不为负）
        double drift = budget - sum(allocated);
        List<String> ids = new ArrayList<>(allocated.keySet());
        for (int i = ids.size() - 1; i >= 0 && Math.abs(drift) > 1e-9; i--) {
            String id = ids.get(i);
            double v = allocated.get(id) + drift;
            if (v >= 0 && v <= capacity.get(id)) {
                allocated.put(id, v);
                drift = 0;
            }
        }

        List<Allocation> result = new ArrayList<>();
        for (DecomposeResourceFeature f : features) {
            String id = f.resourceId();
            result.add(new Allocation(id, allocated.get(id), pe.get(id), algo.get(id),
                    composite.get(id), capacity.get(id), peReady.get(id)));
        }
        return result;
    }

    /** 算法原始分：四种算法各自「看」的特征不同。 */
    static double algoScore(DecomposeAlgoType type, DecomposeResourceFeature f) {
        switch (type) {
            case PROPORTIONAL:
                return f.capacityKwh();
            case COOPERATION:
                return f.cooperation();
            case BENEFIT:
                return f.benefitYuan();
            case CONTROLLABILITY:
                return f.reachability();
            default:
                return f.capacityKwh();
        }
    }

    private static double sum(Map<String, Double> m) {
        double s = 0;
        for (double v : m.values()) {
            s += v;
        }
        return s;
    }

    /** 资源级分配结果。 */
    public static final class Allocation {
        private final String resourceId;
        private final double targetAdjustKwh;
        private final double scorePe;
        private final double scoreAlgo;
        private final double scoreComposite;
        private final double capacityKwh;
        private final boolean peReady;

        Allocation(String resourceId, double targetAdjustKwh, double scorePe, double scoreAlgo,
                   double scoreComposite, double capacityKwh, boolean peReady) {
            this.resourceId = resourceId;
            this.targetAdjustKwh = DecomposeResourceFeature.round2(targetAdjustKwh);
            this.scorePe = scorePe;
            this.scoreAlgo = scoreAlgo;
            this.scoreComposite = scoreComposite;
            this.capacityKwh = capacityKwh;
            this.peReady = peReady;
        }

        public String resourceId() {
            return resourceId;
        }

        public double targetAdjustKwh() {
            return targetAdjustKwh;
        }

        public double scoreComposite() {
            return scoreComposite;
        }

        public double capacityKwh() {
            return capacityKwh;
        }

        public boolean peReady() {
            return peReady;
        }
    }
}
