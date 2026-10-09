package com.openvpp.market.charge;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 事件业务生命周期 —— 平台侧三态：建立 → 发布 → 结束。
 *
 * 与 EventTimeStatus 分工：本状态机管平台业务动作（何时允许申报），
 * EventTimeStatus 按响应时间窗推导执行进度（待响应/响应中/已结束）。
 * 仅"已发布"且未到申报截止时刻的事件受理申报。
 */
public enum EventLifecycle {

    CREATED, PUBLISHED, ENDED;

    private static final Map<EventLifecycle, Set<EventLifecycle>> LEGAL = Map.of(
            CREATED, EnumSet.of(PUBLISHED),
            PUBLISHED, EnumSet.of(ENDED),
            ENDED, EnumSet.noneOf(EventLifecycle.class)
    );

    public void assertTransitTo(EventLifecycle target) {
        if (!LEGAL.get(this).contains(target)) {
            throw new IllegalStateException("非法事件生命周期迁移: " + this + " → " + target);
        }
    }
}
