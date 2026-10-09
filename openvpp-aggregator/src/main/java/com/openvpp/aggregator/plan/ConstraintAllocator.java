package com.openvpp.aggregator.plan;

import com.openvpp.market.charge.DrDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 约束分配器 —— 分级计划的确定性比例分配与尾差处理（第 54 篇底座，
 * 对应验收格"派单可行性"主格）。
 *
 * 硬规则：
 * <ol>
 *   <li>逐级守恒：每级 Σ分配 + 缺口 = 目标，恒等式在草案构造时校验；</li>
 *   <li>按占比分配：权重 = 已确认申报量（一级为运营商申报合计，二级为桩申报量），
 *       上限 = 该主体本级的有效容量 —— "不超申报量、每级另校验可信容量、
 *       不越物理边界"由上限口径一次性封死（有效容量在服务层按
 *       min(申报, 方向可信容量, 方向可调余量) 先封顶再归并）；</li>
 *   <li>确定性尾差：分配步长 0.1 kW；先按占比向下取整，尾差按分配对象标识
 *       升序的固定顺序逐格补发 —— 同一余数永远落在同一主体，同输入必同输出；</li>
 *   <li>缺口显性：Σ有效容量 &lt; 目标时，各行取满上限、缺口明示于草案，
 *       不抹平、不四舍五入进任何一行（部分分配成功 ≠ 总目标可兑现）。</li>
 * </ol>
 *
 * 教学实现为纯函数：无状态、无 I/O、无时钟，输入输出全部显式传参，可单测对拍。
 */
public class ConstraintAllocator {

    /** 分配步长 0.1 kW（100 W 粒度）：向下取整与尾差补发的最小单位 */
    static final BigDecimal STEP_KW = new BigDecimal("0.1");
    /** 占比除法中间值保留位数（取整前用，避免除不尽丢精度） */
    private static final int DIVIDE_SCALE = 10;

    /** 一级分配输入：单个运营商的有效容量行（字段由服务层按桩封顶后归并产出） */
    public static final class OperatorCapacity {
        private final String operatorTenantId;
        /** 已确认申报合计（kW）—— 分配权重 */
        private final BigDecimal declaredKw;
        /**
         * 有效容量（kW）= Σ min(单桩申报, 方向可信容量列, 方向可调余量)。
         * 先按桩封顶再归并，而不是先加总再封顶 —— 否则单桩越界会被总量稀释。
         */
        private final BigDecimal effectiveKw;
        /** 下属场站基线合计（kW）—— 一级行基线留痕 */
        private final BigDecimal baselineKw;

        public OperatorCapacity(String operatorTenantId, BigDecimal declaredKw,
                                BigDecimal effectiveKw, BigDecimal baselineKw) {
            if (operatorTenantId == null || operatorTenantId.isBlank()) {
                throw new IllegalArgumentException("运营商租户标识不能为空");
            }
            this.operatorTenantId = operatorTenantId;
            this.declaredKw = requireNonNegative(declaredKw, "申报合计");
            this.effectiveKw = requireNonNegative(effectiveKw, "有效容量");
            this.baselineKw = requireNonNegative(baselineKw, "基线合计");
        }

        public String getOperatorTenantId() {
            return operatorTenantId;
        }

        public BigDecimal getDeclaredKw() {
            return declaredKw;
        }

        public BigDecimal getEffectiveKw() {
            return effectiveKw;
        }

        public BigDecimal getBaselineKw() {
            return baselineKw;
        }
    }

    /** 二级分配输入：单桩的有效容量行 */
    public static final class PileCapacity {
        private final String resourceId;
        private final String stationId;
        private final String snapshotVersion;
        /** 该桩申报量（kW）—— 分配权重 */
        private final BigDecimal declaredKw;
        /** 有效容量（kW）= min(申报, 方向可信容量列, 方向可调余量) */
        private final BigDecimal effectiveKw;
        /** 该桩历史基线（kW）—— 目标换算参照 */
        private final BigDecimal baselineKw;

        public PileCapacity(String resourceId, String stationId, String snapshotVersion,
                            BigDecimal declaredKw, BigDecimal effectiveKw, BigDecimal baselineKw) {
            if (resourceId == null || resourceId.isBlank()) {
                throw new IllegalArgumentException("桩资源标识不能为空");
            }
            if (stationId == null || stationId.isBlank()) {
                throw new IllegalArgumentException("归属场站不能为空");
            }
            if (snapshotVersion == null || snapshotVersion.isBlank()) {
                throw new IllegalArgumentException("能力快照版本不能为空");
            }
            this.resourceId = resourceId;
            this.stationId = stationId;
            this.snapshotVersion = snapshotVersion;
            this.declaredKw = requireNonNegative(declaredKw, "申报量");
            this.effectiveKw = requireNonNegative(effectiveKw, "有效容量");
            this.baselineKw = requireNonNegative(baselineKw, "基线");
        }

        public String getResourceId() {
            return resourceId;
        }

        public String getStationId() {
            return stationId;
        }

        public String getSnapshotVersion() {
            return snapshotVersion;
        }

        public BigDecimal getDeclaredKw() {
            return declaredKw;
        }

        public BigDecimal getEffectiveKw() {
            return effectiveKw;
        }

        public BigDecimal getBaselineKw() {
            return baselineKw;
        }
    }

    /**
     * 一级分配：平台 → 运营商（按已确认申报占比，确定性尾差）。
     *
     * @param targetAdjustKw 事件目标调节量（kW，&gt; 0）
     * @param direction      事件方向（一级行不换算目标功率，方向透传留痕）
     * @param operators      候选运营商（无序，内部按租户标识升序定序）
     * @return 分配草案（Σ分配 + 缺口 = 目标；缺口 &gt; 0 即申报不足）
     */
    public AllocationDraft allocateToOperators(BigDecimal targetAdjustKw, DrDirection direction,
                                               List<OperatorCapacity> operators) {
        Objects.requireNonNull(direction, "调节方向不能为空");
        requirePositiveTarget(targetAdjustKw);
        // 与 split 内部同一定序：按分配对象标识升序，保证行序即尾差补发序
        List<OperatorCapacity> sorted = new ArrayList<>();
        if (operators != null) {
            sorted.addAll(operators);
        }
        sorted.sort(Comparator.comparing(OperatorCapacity::getOperatorTenantId));
        List<Entry> entries = new ArrayList<>();
        for (OperatorCapacity operator : sorted) {
            entries.add(new Entry(operator.getOperatorTenantId(),
                    operator.getDeclaredKw(), operator.getEffectiveKw()));
        }
        Split split = split(targetAdjustKw, entries);
        List<AllocationLine> lines = new ArrayList<>();
        for (int i = 0; i < split.shares.size(); i++) {
            OperatorCapacity operator = sorted.get(i);
            lines.add(AllocationLine.operatorLine(operator.getOperatorTenantId(),
                    split.shares.get(i), operator.getBaselineKw()));
        }
        return new AllocationDraft(targetAdjustKw, split.gapKw, lines);
    }

    /**
     * 二级分配：运营商 → 桩（按桩申报占比，确定性尾差，逐行换算绝对目标功率）。
     *
     * @param parentAdjustKw 上级份额（该运营商分得的调节量，kW，≥ 0；0 表示零容量份额，产出空目标行）
     * @param direction      事件方向（决定基线加/减）
     * @param piles          候选桩（无序，内部按资源标识升序定序）
     * @return 分配草案（Σ分配 + 缺口 = 上级份额；缺口 &gt; 0 即本运营商下属桩吸不满份额）
     */
    public AllocationDraft allocateToPiles(BigDecimal parentAdjustKw, DrDirection direction,
                                           List<PileCapacity> piles) {
        Objects.requireNonNull(direction, "调节方向不能为空");
        if (parentAdjustKw == null || parentAdjustKw.signum() < 0) {
            throw new IllegalArgumentException("上级份额不得为负: " + parentAdjustKw);
        }
        // 与 split 内部同一定序：按桩资源标识升序，保证行序即尾差补发序
        List<PileCapacity> sorted = new ArrayList<>();
        if (piles != null) {
            sorted.addAll(piles);
        }
        sorted.sort(Comparator.comparing(PileCapacity::getResourceId));
        if (parentAdjustKw.signum() == 0) {
            // 零份额：各行调节量归 0，目标功率回到基线（零调节即回原状），不产生缺口
            List<AllocationLine> zeroLines = new ArrayList<>();
            for (PileCapacity pile : sorted) {
                zeroLines.add(AllocationLine.pileLine(pile.getResourceId(), pile.getStationId(),
                        BigDecimal.ZERO, pile.getBaselineKw(),
                        DirectionalTargetConverter.toTargetPower(direction, pile.getBaselineKw(), BigDecimal.ZERO),
                        pile.getSnapshotVersion()));
            }
            return new AllocationDraft(BigDecimal.ZERO, BigDecimal.ZERO, zeroLines);
        }
        List<Entry> entries = new ArrayList<>();
        for (PileCapacity pile : sorted) {
            entries.add(new Entry(pile.getResourceId(), pile.getDeclaredKw(), pile.getEffectiveKw()));
        }
        Split split = split(parentAdjustKw, entries);
        List<AllocationLine> lines = new ArrayList<>();
        for (int i = 0; i < split.shares.size(); i++) {
            PileCapacity pile = sorted.get(i);
            BigDecimal target = DirectionalTargetConverter.toTargetPower(
                    direction, pile.getBaselineKw(), split.shares.get(i));
            lines.add(AllocationLine.pileLine(pile.getResourceId(), pile.getStationId(),
                    split.shares.get(i), pile.getBaselineKw(), target, pile.getSnapshotVersion()));
        }
        return new AllocationDraft(parentAdjustKw, split.gapKw, lines);
    }

    // ---------------- 内部：确定性比例拆分 ----------------

    /** 拆分输入：分配对象标识 + 权重（申报） + 上限（有效容量） */
    private static final class Entry {
        private final String key;
        private final BigDecimal weight;
        private final BigDecimal cap;

        private Entry(String key, BigDecimal weight, BigDecimal cap) {
            this.key = key;
            this.weight = weight;
            this.cap = cap;
        }
    }

    private static final class Split {
        private final List<BigDecimal> shares;
        private final BigDecimal gapKw;

        private Split(List<BigDecimal> shares, BigDecimal gapKw) {
            this.shares = shares;
            this.gapKw = gapKw;
        }
    }

    /**
     * 确定性比例拆分（核心不变式：Σ份额 + 缺口 = 目标，逐条份额 ≤ 上限）：
     * <ol>
     *   <li>按 key 升序定序 —— 尾差落点由标识序唯一决定，与输入顺序无关；</li>
     *   <li>Σ上限 &lt; 目标：各行取满上限，缺口 = 目标 − Σ上限；</li>
     *   <li>否则按权重占比向下取整（步长 0.1 kW），裁剪到上限内，
     *       尾差按序逐格补发直至补完（Σ上限 ≥ 目标保证补得完）。</li>
     * </ol>
     */
    private static Split split(BigDecimal targetKw, List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(e -> e.key));

        BigDecimal totalCap = sorted.stream()
                .map(e -> e.cap)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> shares = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            shares.add(BigDecimal.ZERO.setScale(1));
        }

        if (totalCap.compareTo(targetKw) < 0) {
            for (int i = 0; i < sorted.size(); i++) {
                shares.set(i, sorted.get(i).cap);
            }
            return new Split(shares, targetKw.subtract(totalCap));
        }

        BigDecimal totalWeight = sorted.stream()
                .map(e -> e.weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() > 0) {
            for (int i = 0; i < sorted.size(); i++) {
                Entry entry = sorted.get(i);
                BigDecimal raw = targetKw.multiply(entry.weight)
                        .divide(totalWeight, DIVIDE_SCALE, RoundingMode.DOWN);
                BigDecimal share = raw.setScale(1, RoundingMode.DOWN);
                // 占比份额裁剪到有效上限内（封顶先于尾差补发）
                if (share.compareTo(entry.cap) > 0) {
                    share = entry.cap;
                }
                shares.set(i, share);
            }
        }
        // 尾差按序补发：每格 0.1 kW，补到该对象上限为止；Σ上限 ≥ 目标保证必然补完
        // （leftover = 目标 − Σ份额 ≤ Σ上限 − Σ份额 = 剩余可补空间，不变式逐格保持）
        BigDecimal leftover = targetKw.subtract(sum(shares));
        int cursor = 0;
        while (leftover.signum() > 0) {
            Entry entry = sorted.get(cursor);
            BigDecimal share = shares.get(cursor);
            if (share.add(STEP_KW).compareTo(entry.cap) <= 0) {
                shares.set(cursor, share.add(STEP_KW));
                leftover = leftover.subtract(STEP_KW);
            }
            cursor = (cursor + 1) % sorted.size();
        }
        return new Split(shares, BigDecimal.ZERO.setScale(1));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal requirePositiveTarget(BigDecimal targetKw) {
        if (targetKw == null || targetKw.signum() <= 0) {
            throw new IllegalArgumentException("目标调节量必须为正: " + targetKw);
        }
        return targetKw;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + "不得为负: " + value);
        }
        return value;
    }
}
