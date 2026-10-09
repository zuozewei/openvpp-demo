package com.openvpp.aggregator.plan;

import com.openvpp.market.charge.DrDirection;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 方向性目标换算器 —— "基线 ± 调节量 → 绝对目标功率"的唯一换算入口（第 54 篇底座）。
 *
 * 口径与 {@code ChargePileDownlinkChannel} 契约第 1 条同向：换算属派单链路侧职责，
 * 发生在设备通道入口之前 —— 通道只接收换算完成的绝对功率目标，永不接收调节量、
 * 不感知基线；功率目标与调节量不混写。
 *
 * 两方向分列，不得混用：
 * <ul>
 *   <li>削峰（压低在充功率）：target = baseline − adjust，下界 0（压到停机为极限）；</li>
 *   <li>填谷（抬升在充功率）：target = baseline + adjust，上界额定功率（充到额为极限）。</li>
 * </ul>
 *
 * 边界保证由分配侧承担（{@link ConstraintAllocator} 按方向可调余量封顶：
 * 削峰余量 = 基线、填谷余量 = 额定 − 基线），本换算器只做纯函数换算，并对负结果
 * 硬拒绝 —— 负目标不是"零目标"：零目标合法（停机/回原状），负目标是口径错误。
 */
public final class DirectionalTargetConverter {

    private DirectionalTargetConverter() {
    }

    /**
     * 按事件方向把"基线 ± 调节量"换算为绝对目标功率。
     *
     * @param direction  事件调节方向（削峰减、填谷加）
     * @param baselineKw 历史基线（kW，≥ 0）
     * @param adjustKw   调节量（kW，≥ 0）
     * @return 绝对目标功率（kW，≥ 0；削峰可为 0，填谷可等于额定）
     * @throws IllegalStateException 削峰调节量超过基线（分配侧未按余量封顶，属口径错误）
     */
    public static BigDecimal toTargetPower(DrDirection direction, BigDecimal baselineKw, BigDecimal adjustKw) {
        Objects.requireNonNull(direction, "调节方向不能为空");
        Objects.requireNonNull(baselineKw, "基线不能为空");
        Objects.requireNonNull(adjustKw, "调节量不能为空");
        if (baselineKw.signum() < 0) {
            throw new IllegalArgumentException("基线不得为负: " + baselineKw);
        }
        if (adjustKw.signum() < 0) {
            throw new IllegalArgumentException("调节量不得为负: " + adjustKw);
        }
        BigDecimal target = direction == DrDirection.PEAK_SHAVE
                ? baselineKw.subtract(adjustKw)
                : baselineKw.add(adjustKw);
        if (target.signum() < 0) {
            throw new IllegalStateException("换算出现负目标功率（削峰调节量超过基线）: 基线 " + baselineKw
                    + " kW，调节量 " + adjustKw + " kW —— 分配侧未按方向余量封顶");
        }
        return target;
    }
}
