package com.openvpp.iot.simulate;

import java.util.Collections;
import java.util.Set;

/**
 * 故障注入档案 —— 模拟充电桩通道的异常行为配置。
 *
 * 【教学用途声明】本类仅供测试与演示：为第 55 篇「监测与质量」验收格构造重复、
 * 迟到、乱序、缺失、方向性偏差与失联六类场景，验证平台侧的质量判定、方向性告警
 * 与超时核查逻辑；生产路径不存在"注入开关"，真实设备异常由链路自然产生。
 *
 * 全部注入都是确定性行为，不依赖 wall-clock 抖动：同一档案 + 同一种子 + 同一目标
 * 序列构造的通道，产出的样点流逐字段一致——故障场景可复现、验收可对拍
 * （第 59 篇"故障注入与干净环境复跑"的前置条件）。
 *
 * 六种注入与平台侧被验判定一一对应：
 *   重复 DUPLICATE    —— 每 duplicateEveryN 个样点，紧跟随原样点再发一份（同资源、
 *                       同序号、同采样时标、同数值），考验按（序号, 采样时标）去重；
 *   迟到 LATE         —— 接收时标 = 采样时标 + lateDelayMs，考验迟到识别，以及
 *                       "迟到样点不能充当本指令达标证明"的关联判定；
 *   乱序 OUT_OF_ORDER —— 相邻样点两两交换发出顺序（样点自身时标不变），考验按采样
 *                       时标排序与单调性校验；
 *   缺失 MISSING      —— missingSeqs 指定的序号被消费但不产出（时间照走、样点缺席），
 *                       流中出现缺口，考验"缺失不补 0"（有效零值与缺失分列）；
 *   方向性偏差 BIAS    —— 实测 = 目标 + 波动 + 恒定偏移 biasKw（负值 = 系统性偏低，
 *                       如削峰场景桩功率压不下来），考验削峰/填谷方向的超差告警判对方向；
 *   失联 OFFLINE       —— 下行无回执（Optional.empty()）且遥测断流，考验超时转核查
 *                       而不是判失败重发。
 *
 * {@link #none()} 表示全部不注入（健康设备）。
 */
public class FaultInjectionProfile {

    /** 失联：下行无回执 + 遥测断流 */
    private final boolean offline;

    /** 重复：每 N 个样点注入 1 个重复样点；0 = 不注入 */
    private final int duplicateEveryN;

    /** 迟到：接收时标 = 采样时标 + 该时延（毫秒）；0 = 不注入 */
    private final long lateDelayMs;

    /** 缺失：这些序号不产出（缺口）；空集 = 不注入 */
    private final Set<Long> missingSeqs;

    /** 乱序：相邻样点两两交换发出顺序 */
    private final boolean outOfOrder;

    /** 方向性偏差：实测 = 目标 + 波动 + 该偏移（kW，负值 = 系统性偏低）；0 = 不注入 */
    private final double biasKw;

    public FaultInjectionProfile(boolean offline, int duplicateEveryN, long lateDelayMs,
                                 Set<Long> missingSeqs, boolean outOfOrder, double biasKw) {
        if (duplicateEveryN < 0) {
            throw new IllegalArgumentException("duplicateEveryN 不得为负: " + duplicateEveryN);
        }
        if (lateDelayMs < 0) {
            throw new IllegalArgumentException("lateDelayMs 不得为负: " + lateDelayMs);
        }
        this.offline = offline;
        this.duplicateEveryN = duplicateEveryN;
        this.lateDelayMs = lateDelayMs;
        this.missingSeqs = missingSeqs == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(Set.copyOf(missingSeqs));
        this.outOfOrder = outOfOrder;
        this.biasKw = biasKw;
    }

    /** 健康设备：全部注入关闭 */
    public static FaultInjectionProfile none() {
        return new FaultInjectionProfile(false, 0, 0L, Collections.emptySet(), false, 0.0);
    }

    public boolean isOffline() {
        return offline;
    }

    public int getDuplicateEveryN() {
        return duplicateEveryN;
    }

    public long getLateDelayMs() {
        return lateDelayMs;
    }

    public Set<Long> getMissingSeqs() {
        return missingSeqs;
    }

    public boolean isOutOfOrder() {
        return outOfOrder;
    }

    public double getBiasKw() {
        return biasKw;
    }

    @Override
    public String toString() {
        return "FaultInjectionProfile{offline=" + offline
                + ", duplicateEveryN=" + duplicateEveryN
                + ", lateDelayMs=" + lateDelayMs
                + ", missingSeqs=" + missingSeqs
                + ", outOfOrder=" + outOfOrder
                + ", biasKw=" + biasKw
                + '}';
    }
}
