package com.openvpp.settlement.charge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算计算器 —— 四量计算与三方分账的纯函数（第 57 篇底座）。
 *
 * 口径（对照姊妹专栏《虚拟电厂系统开发实战》第 21 篇与既有
 * {@code ProfitAllocator} 思想：总额守恒、残差归并，不另造相反公式）：
 *
 * <ol>
 *   <li>补偿毛额 = 核定响应电量 × 补偿单价（元，保留 2 位，HALF_UP）；</li>
 *   <li>净实收 = 毛额 − 考核扣款（扣款上限守卫见 {@link SettlementOrder}）；</li>
 *   <li>分账：按快照比例对净实收逐方计算，单方金额 = 净实收 × 比例
 *       （元，保留 2 位，DOWN）；尾差（±0.01~0.02 级舍入残差）归并比例最大方
 *       —— 与 ProfitAllocator"残差归并最大贡献者"同一思想；并列时按
 *       平台 → 运营商 → 场站的固定顺序取先者，保证同输入同输出；</li>
 *   <li>守恒底线：分账合计恒等于净实收，分毫不差；净实收为 0 时
 *       各方均为 0 —— 不存在可分配净实收时不生成正向收益。</li>
 * </ol>
 *
 * 全静态无状态：同样的输入必产出同样的分账结果（确定性是幂等重试
 * 与更正复算的前提）。
 */
final class SettlementCalculator {

    private SettlementCalculator() {
    }

    /** 补偿毛额（元）= 核定电量 × 单价，保留 2 位 */
    static BigDecimal grossYuan(double confirmedKwh, BigDecimal priceYuanPerKwh) {
        return BigDecimal.valueOf(confirmedKwh).multiply(priceYuanPerKwh)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 净实收（元）= 毛额 − 考核扣款 */
    static BigDecimal netYuan(BigDecimal grossYuan, BigDecimal deductionYuan) {
        return grossYuan.subtract(deductionYuan).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 三方分账：比例最大方吸收舍入尾差，合计恒等于净实收。
     *
     * @param netYuan 净实收（元，已保留 2 位）
     * @param rule    分账规则快照（比例总和 = 1 已由快照构造保证）
     * @return 主体 → 应分金额（平台 → 运营商 → 场站顺序）
     */
    static Map<AllocationParty, BigDecimal> allocate(BigDecimal netYuan, SharingRuleSnapshot rule) {
        Map<AllocationParty, BigDecimal> result = new LinkedHashMap<>();
        AllocationParty[] parties = AllocationParty.values();
        List<BigDecimal> rates = rule.orderedRates();

        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < parties.length; i++) {
            BigDecimal share = netYuan.multiply(rates.get(i)).setScale(2, RoundingMode.DOWN);
            result.put(parties[i], share);
            allocated = allocated.add(share);
        }

        // 守恒：舍入尾差归并比例最大方（并列按 平台→运营商→场站 顺序取先者）
        BigDecimal residue = netYuan.subtract(allocated);
        if (residue.signum() != 0) {
            int largestIndex = 0;
            for (int i = 1; i < parties.length; i++) {
                if (rates.get(i).compareTo(rates.get(largestIndex)) > 0) {
                    largestIndex = i;
                }
            }
            result.merge(parties[largestIndex], residue, BigDecimal::add);
        }
        return result;
    }

    /** 组装分账明细行（主体标识由服务层按归属链解析后传入） */
    static List<AllocationLine> lines(Map<AllocationParty, BigDecimal> amounts,
                                      Map<AllocationParty, String> partyIds,
                                      SharingRuleSnapshot rule) {
        List<AllocationLine> lines = new ArrayList<>();
        for (AllocationParty party : AllocationParty.values()) {
            lines.add(new AllocationLine(party, partyIds.get(party),
                    rule.orderedRates().get(party.ordinal()), amounts.get(party)));
        }
        return lines;
    }
}
