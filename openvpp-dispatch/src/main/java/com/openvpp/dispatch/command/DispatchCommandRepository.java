package com.openvpp.dispatch.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单桩指令仓库 —— 内存版指令登记簿（第 54 篇底座）。
 *
 * 登记口径：按业务指令编号唯一登记（设备侧去重键同口径），重复登记即拒 ——
 * 已登记指令是下发证据，不可被同名新指令覆盖。
 *
 * 教学实现为单 JVM 内存版（synchronized 串行化）；分布式部署时指令需落库
 * （编号唯一约束），口径不变。
 */
public class DispatchCommandRepository {

    private final Map<String, DispatchCommand> byCommandNo = new LinkedHashMap<>();

    /** 登记指令（同编号重复登记即拒） */
    public synchronized void save(DispatchCommand command) {
        if (byCommandNo.putIfAbsent(command.getCommandNo(), command) != null) {
            throw new IllegalStateException("指令编号已登记，禁止覆盖: " + command.getCommandNo());
        }
    }

    public synchronized Optional<DispatchCommand> find(String commandNo) {
        return Optional.ofNullable(byCommandNo.get(commandNo));
    }

    public synchronized DispatchCommand require(String commandNo) {
        DispatchCommand command = byCommandNo.get(commandNo);
        if (command == null) {
            throw new IllegalArgumentException("未登记指令: " + commandNo);
        }
        return command;
    }

    /** 指定计划版本的全部指令（按编号升序，不可变视图） */
    public synchronized List<DispatchCommand> findByPlan(String planId, int planVersion) {
        List<DispatchCommand> matched = new ArrayList<>();
        for (DispatchCommand command : byCommandNo.values()) {
            if (command.getPlanId().equals(planId) && command.getPlanVersion() == planVersion) {
                matched.add(command);
            }
        }
        matched.sort(Comparator.comparing(DispatchCommand::getCommandNo));
        return List.copyOf(matched);
    }
}
