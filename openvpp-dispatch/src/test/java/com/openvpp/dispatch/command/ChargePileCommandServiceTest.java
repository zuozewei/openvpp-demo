package com.openvpp.dispatch.command;

import com.openvpp.iot.simulate.ChargePileDownlinkChannel;
import com.openvpp.iot.simulate.CommandReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单桩指令服务单测（第 54 篇底座，对应验收格"下发证据"）：
 * 发送与回执时标记录、失联无回执进入核查、重复下发幂等、
 * 补发换新编号并关联原编号、零目标合法下发、回执对账。
 */
class ChargePileCommandServiceTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 8, 13, 45);

    /** 记录每次通道调用的桩号 / 目标功率 / 指令编号，并按编号脚本化回执 */
    private static final class StubChannel implements ChargePileDownlinkChannel {
        private final List<String[]> calls = new ArrayList<>();
        private Function<String, Optional<CommandReceipt>> responder = commandNo -> Optional.empty();

        @Override
        public Optional<CommandReceipt> sendTargetPower(String resourceId, BigDecimal targetKw, String commandNo) {
            calls.add(new String[]{resourceId, targetKw.toPlainString(), commandNo});
            return responder.apply(commandNo);
        }
    }

    private StubChannel channel;
    private ChargePileCommandService service;

    @BeforeEach
    void setUp() {
        channel = new StubChannel();
        service = new ChargePileCommandService(new DispatchCommandRepository(), channel);
    }

    private static DispatchCommand newCommand(String commandNo, String targetKw) {
        // 证据三列：调节量 75 / 基线 120 / 绝对目标 45（削峰：120 − 75 = 45）
        return new DispatchCommand(commandNo, "PLAN-001", 1,
                "ev-charge-s-11101", "S-11101",
                new BigDecimal("75"), new BigDecimal("120"), new BigDecimal(targetKw), CREATED_AT);
    }

    private CommandReceipt receipt(String commandNo, String targetKw,
                                    CommandReceipt.Status status, String reason, long ackedAtMs) {
        return new CommandReceipt(commandNo, "ev-charge-s-11101",
                new BigDecimal(targetKw), status, reason, ackedAtMs);
    }

    @Test
    void 发送并记录受理回执时标且下发的是绝对目标() {
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "45", CommandReceipt.Status.ACCEPTED, null, 1_728_360_000_000L));

        DispatchCommand command = service.issue(newCommand("CMD-5401", "45"));

        assertEquals(CommandState.ACKED, command.getState());
        assertNotNull(command.getSentAt(), "发送时标必须记录");
        assertEquals(1_728_360_000_000L, command.getReceiptAckedAtMs(), "回执时标（设备侧毫秒）必须记录");
        assertEquals("ACCEPTED", command.getReceiptStatus());
        assertNull(command.getRejectReason());
        // 通道只收到绝对目标功率，调节量不投递（证据三列分列）
        assertEquals(1, channel.calls.size());
        assertEquals("ev-charge-s-11101", channel.calls.get(0)[0]);
        assertEquals("45", channel.calls.get(0)[1], "下发值是绝对目标功率而非调节量");
        assertEquals("CMD-5401", channel.calls.get(0)[2]);
        // 调节量只留痕
        assertEquals(0, new BigDecimal("75").compareTo(command.getAdjustKw()));
        assertEquals(0, new BigDecimal("120").compareTo(command.getBaselineKw()));
    }

    @Test
    void 失联记无回执进入核查而非失败断言() {
        channel.responder = commandNo -> Optional.empty();

        DispatchCommand command = service.issue(newCommand("CMD-5402", "45"));

        assertEquals(CommandState.NO_RECEIPT, command.getState(), "失联进入核查");
        assertNull(command.getReceiptAckedAtMs(), "无回执不留回执时标");
        assertNull(command.getReceiptStatus());
        assertNotNull(command.getSentAt(), "失联也有发送证据（登记先于下发）");
    }

    @Test
    void 拒绝回执记录原因码() {
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "45", CommandReceipt.Status.REJECTED, "TARGET_OUT_OF_RANGE", 999L));

        DispatchCommand command = service.issue(newCommand("CMD-5403", "45"));

        assertEquals(CommandState.REJECTED, command.getState());
        assertEquals("TARGET_OUT_OF_RANGE", command.getRejectReason());
        assertEquals(999L, command.getReceiptAckedAtMs(), "拒绝回执同样有回执时标");
        assertEquals("REJECTED", command.getReceiptStatus());
    }

    @Test
    void 重复下发幂等拦截不重复投递() {
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "45", CommandReceipt.Status.ACCEPTED, null, 1L));
        DispatchCommand first = service.issue(newCommand("CMD-5404", "45"));

        // 同编号重复 issue（甚至换了目标功率）直接返回已登记指令，不再触碰通道
        DispatchCommand retry = service.issue(newCommand("CMD-5404", "99"));

        assertSame(first, retry, "同编号重复下发返回原指令");
        assertEquals(1, channel.calls.size(), "重复下发不重复投递（设备按编号去重，重试不生成重复指令）");
        assertEquals(CommandState.ACKED, retry.getState());
    }

    @Test
    void 无回执指令补发换新编号并关联原编号() {
        channel.responder = commandNo -> Optional.empty();
        DispatchCommand original = service.issue(newCommand("CMD-5405", "45"));
        assertEquals(CommandState.NO_RECEIPT, original.getState());

        // 核查后补发：新编号 + 关联原编号（设备按新编号受理）
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "45", CommandReceipt.Status.ACCEPTED, null, 2L));
        DispatchCommand successor = service.reissue("CMD-5405", "CMD-5405-R1");

        assertEquals(CommandState.ACKED, successor.getState());
        assertEquals("CMD-5405", successor.getOriginalCommandNo(), "补发指令关联原编号");
        assertEquals("PLAN-001", successor.getPlanId(), "计划关联原样继承");
        assertEquals(1, successor.getPlanVersion());
        assertEquals(0, new BigDecimal("45").compareTo(successor.getTargetPowerKw()));
        assertEquals(2, channel.calls.size());
        assertEquals("CMD-5405-R1", channel.calls.get(1)[2], "补发以新编号投递");
        // 原指令状态不被补发改写（证据留痕）
        assertEquals(CommandState.NO_RECEIPT, service.require("CMD-5405").getState());
    }

    @Test
    void 已受理指令在途禁止补发() {
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "45", CommandReceipt.Status.ACCEPTED, null, 1L));
        service.issue(newCommand("CMD-5406", "45"));

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> service.reissue("CMD-5406", "CMD-5406-R1"));
        assertTrue(rejected.getMessage().contains("补发"));
        assertEquals(1, channel.calls.size(), "禁止补发则不产生新投递");
    }

    @Test
    void 零目标功率合法下发() {
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "0", CommandReceipt.Status.ACCEPTED, null, 1L));

        DispatchCommand command = service.issue(newCommand("CMD-5407", "0"));

        assertEquals(CommandState.ACKED, command.getState(), "零目标（停机/回原状）合法，不得拒绝下发");
        assertEquals("0", channel.calls.get(0)[1], "通道收到的是零目标绝对功率");
    }

    @Test
    void 回执目标回显与指令不符时拒记并保持待回执() {
        // 设备回显的目标与指令不一致：下发证据链不允许错挂
        channel.responder = commandNo -> Optional.of(
                receipt(commandNo, "99", CommandReceipt.Status.ACCEPTED, null, 1L));

        DispatchCommand command = newCommand("CMD-5408", "45");
        assertThrows(IllegalArgumentException.class, () -> service.issue(command));
        assertEquals(CommandState.SENT, command.getState(), "对账失败的指令留在已发送态待核查");
    }

    @Test
    void 指令按编号唯一登记不可覆盖() {
        DispatchCommandRepository repository = new DispatchCommandRepository();
        repository.save(newCommand("CMD-5409", "45"));

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> repository.save(newCommand("CMD-5409", "60")));
        assertTrue(duplicate.getMessage().contains("禁止覆盖"));
        assertEquals(0, new BigDecimal("45").compareTo(repository.require("CMD-5409").getTargetPowerKw()));
    }
}
