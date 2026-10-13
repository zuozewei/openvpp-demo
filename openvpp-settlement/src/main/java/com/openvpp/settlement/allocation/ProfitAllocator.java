package com.openvpp.settlement.allocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收益分摊器 —— 平台收益分配到聚合的各用户。
 * "保底 + 分成"模式（第 01 篇术语表）：
 * 1. 保底：按各用户申报容量占比分配保底收益（旱涝保收，降低参与门槛）；
 * 2. 分成：超出保底的部分按实际贡献（各资源实际响应量占比）分配；
 * 3. 平台服务费：从分成部分按比例计提，先四舍五入到分位，再分配余额。
 * 守恒底线：分配总额 + 服务费 = 平台实收，分毫不差（分位残差归并最大贡献者）。
 * 输入契约（违反即抛 IllegalArgumentException，异常场次走合同处置流程，不做普通分摊）：
 * 金额非负且不超过分位精度；服务费率在 [0,1)；申报与响应逐户非负、主体集合一致、
 * 申报容量总和为正、总响应量大于零（负容量/负响应会产生反向账单，负考核须另立扣款科目，
 * 不作为本分摊器的贡献权重）；保底总额不超过实收（预估实收的合同比例上限不在本器校验）。
 */
public class ProfitAllocator {

    /** 平台服务费比例（分成部分），取值 [0,1) */
    private final BigDecimal platformCutRate;

    public ProfitAllocator(BigDecimal platformCutRate) {
        if (platformCutRate == null || platformCutRate.signum() < 0
                || platformCutRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("服务费率必须在 [0,1) 区间: " + platformCutRate);
        }
        this.platformCutRate = platformCutRate;
    }

    /**
     * 分摊一次 DR 收益。
     *
     * @param totalSettleYuan 平台实收结算金额（第 19 篇 DR 结算产出），非负且精度不超过分位
     * @param guaranteedYuan  保底总额（按容量占比分），非负、不超过实收、精度不超过分位
     * @param declaredKw      用户 → 申报容量（总和必须为正，键集与响应一致）
     * @param actualKwh       用户 → 实际响应量（贡献口径，总和必须为正）
     * @return 用户 → 应分金额（分位精度，总和 + 服务费 = 实收，分毫不差）
     */
    public Map<String, BigDecimal> allocate(BigDecimal totalSettleYuan, BigDecimal guaranteedYuan,
                                            Map<String, BigDecimal> declaredKw,
                                            Map<String, BigDecimal> actualKwh) {
        if (totalSettleYuan == null || guaranteedYuan == null
                || totalSettleYuan.signum() < 0 || guaranteedYuan.signum() < 0) {
            throw new IllegalArgumentException("金额必须非负");
        }
        if (totalSettleYuan.scale() > 2 || guaranteedYuan.scale() > 2) {
            throw new IllegalArgumentException("金额精度不得超过分位（2 位小数）");
        }
        if (declaredKw == null || actualKwh == null || declaredKw.isEmpty()) {
            throw new IllegalArgumentException("申报与响应数据不得为空");
        }
        if (!declaredKw.keySet().equals(actualKwh.keySet())) {
            throw new IllegalArgumentException("申报与响应的主体集合必须一致");
        }
        for (Map.Entry<String, BigDecimal> e : declaredKw.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw new IllegalArgumentException("申报容量必须逐户非负: " + e.getKey());
            }
        }
        for (Map.Entry<String, BigDecimal> e : actualKwh.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw new IllegalArgumentException("响应量必须逐户非负: " + e.getKey());
            }
        }
        if (guaranteedYuan.compareTo(totalSettleYuan) > 0) {
            throw new IllegalArgumentException("保底总额超过实收，须按合同走垫资/处置流程，不做普通分摊");
        }

        BigDecimal totalDeclared = declaredKw.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDeclared.signum() <= 0) {
            throw new IllegalArgumentException("申报容量总和必须为正");
        }
        BigDecimal totalActual = actualKwh.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalActual.signum() <= 0) {
            throw new IllegalArgumentException("总响应量为零：分成池无从按贡献分配，本期不做普通分摊");
        }

        Map<String, BigDecimal> result = new LinkedHashMap<>();

        // 第一层：保底按申报容量占比（分位向下舍入）
        Map<String, BigDecimal> guaranteedShare = new LinkedHashMap<>();
        declaredKw.forEach((user, kw) -> guaranteedShare.put(user,
                guaranteedYuan.multiply(kw).divide(totalDeclared, 2, RoundingMode.DOWN)));

        // 第二层：分成按实际贡献占比——服务费先计提到分位，再分配剩余分成池
        BigDecimal surplus = totalSettleYuan.subtract(guaranteedYuan);
        BigDecimal fee = surplus.multiply(platformCutRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal distributable = surplus.subtract(fee);

        BigDecimal allocated = BigDecimal.ZERO;
        String topContributor = null;
        BigDecimal topContribution = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : actualKwh.entrySet()) {
            BigDecimal share = distributable.multiply(e.getValue())
                    .divide(totalActual, 2, RoundingMode.DOWN);
            BigDecimal total = guaranteedShare.getOrDefault(e.getKey(), BigDecimal.ZERO).add(share);
            result.put(e.getKey(), total);
            allocated = allocated.add(total);
            if (e.getValue().compareTo(topContribution) > 0) {
                topContribution = e.getValue();
                topContributor = e.getKey();
            }
        }

        // 守恒：分位残差归并最大贡献者（各资金池均为分位精度，残差为分位金额）
        BigDecimal residue = guaranteedYuan.add(distributable).subtract(allocated);
        if (residue.signum() != 0 && topContributor != null) {
            result.merge(topContributor, residue, BigDecimal::add);
        }
        return result;
    }
}
