package com.openvpp.iot.simulate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 充电桩下行通道契约 —— 供 dispatch 模块（InstructionService 下行投递）后续装配调用。
 * dispatch 依赖 openvpp-iot 后以本接口替换日志型下行回调，调用方只面向契约编程。
 *
 * 契约四条（调用方不得违反）：
 *
 * 1. 目标语义是<b>绝对功率目标</b>（kW），不是调节量。"基线 ± 调节量 → 目标功率"
 *    的换算属于派单侧职责，换算完成后才进入本接口；本接口不接受调节量，
 *    也不感知基线（第 54 篇"调节量与绝对功率目标不混写"的下发侧落点）。
 *
 * 2. <b>零目标功率合法</b>（停机 / 回原状），调用方不得因目标为 0 拒绝下发；
 *    监测侧对零目标一律按<b>绝对偏差</b> |实测 − 0| 判定超差，不用比例偏差——
 *    比例偏差在目标为 0 时无定义，会把"零目标但有实际功率"的异常掩盖掉
 *    （第 55 篇"零目标用绝对偏差"验收格）。
 *
 * 3. <b>回执与遥测达标是两件事</b>：收到 ACCEPTED 回执只证明受理，"功率到位"只能
 *    由后续遥测样点围绕目标的判定证明；无回执（Optional.empty，失联场景）不是拒绝，
 *    调用方应转入超时核查而不是直接重发（设备可能已执行，重复下发会放大为重复动作）。
 *
 * 4. <b>指令编号是设备的去重键</b>：同一编号重复投递会被设备侧拒绝
 *    （DUPLICATE_COMMAND）；确需重发必须使用新编号并由业务层关联原编号。
 *
 * 教学实现见 {@link SimulatedChargePileChannel}；真实设备接入时以"协议适配实现
 * 本接口"替换模拟实现，调用方零改动（真实接入属工程边界，第 54 篇单列）。
 */
public interface ChargePileDownlinkChannel {

    /**
     * 下发绝对目标功率。
     *
     * @param resourceId 资源标识（桩号）：必须与目标设备一致，否则设备侧拒绝
     *                   （RESOURCE_MISMATCH）
     * @param targetKw   绝对目标功率（kW，非调节量）：零合法；超出设备量程被拒绝
     *                   （TARGET_OUT_OF_RANGE）；NaN 等非法值被拒绝（ILLEGAL_TARGET）
     * @param commandNo  业务指令编号：设备侧按它去重，重复编号拒绝（DUPLICATE_COMMAND）
     * @return 回执（含回执时标）；失联时返回 Optional.empty()——无回执不等于拒绝，
     *         调用方按超时核查处理
     */
    Optional<CommandReceipt> sendTargetPower(String resourceId, BigDecimal targetKw, String commandNo);
}
