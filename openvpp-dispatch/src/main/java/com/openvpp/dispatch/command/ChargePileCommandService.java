package com.openvpp.dispatch.command;

import com.openvpp.iot.simulate.ChargePileDownlinkChannel;
import com.openvpp.iot.simulate.CommandReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 单桩指令服务 —— 分级计划到模拟设备通道的下发编排（第 54 篇底座）。
 *
 * 关键设计（对应验收格"下发证据"与素材"桩级派单与设备控制"行）：
 * <ol>
 *   <li>先登记后下发：指令先登记进仓库（登记先于通道调用，失联也有发送证据），
 *       再经 {@link ChargePileDownlinkChannel} 下发绝对目标功率；</li>
 *   <li>幂等下发：同 commandNo 重复 issue 直接返回已登记指令，不重发 ——
 *       设备侧按编号去重，重复投递只会被 DUPLICATE_COMMAND 拒绝；
 *       重试不生成重复指令；</li>
 *   <li>失联记无回执进入核查：通道返回 Optional.empty 记 NO_RECEIPT ——
 *       无回执 ≠ 设备未执行，禁止直接重发（设备可能已执行，重复下发放大为重复动作），
 *       须经超时核查/遥测佐证后由 {@link #reissue} 换新编号补发；</li>
 *   <li>回执 ≠ 达标：受理回执只证明设备受理，功率到位由后续监测工序按遥测判定
 *       （第 55 篇），本服务不下达标结论；</li>
 *   <li>补发换新编号：仅无回执/已拒绝的指令允许补发，新指令关联原编号
 *       （originalCommandNo），已受理指令在途禁止补发。</li>
 * </ol>
 *
 * 教学实现为单 JVM 内存版（synchronized 串行化）；生产形态以发件箱
 * （本地消息表 + 异步投递）消除"登记成功、通道未投"的窗口期，口径不变。
 */
public class ChargePileCommandService {

    private static final Logger log = LoggerFactory.getLogger(ChargePileCommandService.class);

    private final DispatchCommandRepository repository;
    private final ChargePileDownlinkChannel channel;

    public ChargePileCommandService(DispatchCommandRepository repository,
                                    ChargePileDownlinkChannel channel) {
        this.repository = Objects.requireNonNull(repository, "指令仓库不能为空");
        this.channel = Objects.requireNonNull(channel, "下行通道不能为空");
    }

    /**
     * 创建并下发：CREATED → SENT →（ACKED / REJECTED / NO_RECEIPT）。
     *
     * 同编号重复下发幂等拦截：返回已登记指令，不重复调用通道 —— 重试不生成重复指令。
     * 下发值是绝对目标功率（targetPowerKw），调节量只留痕不投递（通道契约第 1 条）。
     */
    public synchronized DispatchCommand issue(DispatchCommand command) {
        Objects.requireNonNull(command, "指令不能为空");
        DispatchCommand existing = repository.find(command.getCommandNo()).orElse(null);
        if (existing != null) {
            log.info("指令幂等命中: {} 已登记（{}），重复下发被拦截", command.getCommandNo(), existing.getState());
            return existing;
        }
        repository.save(command);
        command.markSent(LocalDateTime.now());
        Optional<CommandReceipt> receipt = channel.sendTargetPower(
                command.getResourceId(), command.getTargetPowerKw(), command.getCommandNo());
        if (receipt.isPresent()) {
            command.recordReceipt(receipt.get());
            if (command.getState() == CommandState.ACKED) {
                log.info("指令受理: {} → {} 目标 {} kW（受理≠到位，达标以遥测判定为准）",
                        command.getCommandNo(), command.getResourceId(), command.getTargetPowerKw());
            } else {
                log.warn("指令被拒: {} → {} 原因 {}", command.getCommandNo(),
                        command.getResourceId(), command.getRejectReason());
            }
        } else {
            command.markNoReceipt();
            log.warn("指令无回执（失联）: {} → {}，进入核查 —— 不直接重发，待核查后换新编号补发",
                    command.getCommandNo(), command.getResourceId());
        }
        return command;
    }

    /**
     * 换新编号补发：原指令须为无回执/已拒绝，新编号生成 successor 并关联原编号。
     * 已受理指令在途禁止补发（会放大为设备重复动作）；同新编号重复补发幂等拦截。
     */
    public synchronized DispatchCommand reissue(String originalCommandNo, String newCommandNo) {
        DispatchCommand original = repository.require(originalCommandNo);
        if (!original.getState().isReissuable()) {
            throw new IllegalStateException("仅无回执/已拒绝的指令允许补发: " + originalCommandNo
                    + " 当前 " + original.getState() + "（已受理指令在途，补发会放大为设备重复动作）");
        }
        DispatchCommand successor = original.successor(newCommandNo, LocalDateTime.now());
        log.info("指令补发: {} → 新编号 {}（关联原编号），计划 {} v{}",
                originalCommandNo, newCommandNo, original.getPlanId(), original.getPlanVersion());
        return issue(successor);
    }

    public DispatchCommand require(String commandNo) {
        return repository.require(commandNo);
    }

    public Optional<DispatchCommand> find(String commandNo) {
        return repository.find(commandNo);
    }
}
