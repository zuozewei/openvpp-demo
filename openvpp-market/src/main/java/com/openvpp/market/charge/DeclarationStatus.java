package com.openvpp.market.charge;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 场站申报状态机 —— 申报单的完整生命周期（第 53 篇底座）。
 *
 * 状态图：
 *   SUBMITTED(已提交) ──运营商确认──> CONFIRMED(已确认)
 *   SUBMITTED ──运营商拒绝──> REJECTED(已拒绝)
 *   SUBMITTED / CONFIRMED ──场站撤回──> WITHDRAWN(已撤回)
 *
 * 只有 CONFIRMED 的申报才进入后续派单（第 54 篇）；
 * 撤回与拒绝均释放容量预占（台账留痕），REJECTED / WITHDRAWN 为终态。
 */
public enum DeclarationStatus {

    SUBMITTED, CONFIRMED, REJECTED, WITHDRAWN;

    private static final Map<DeclarationStatus, Set<DeclarationStatus>> LEGAL = Map.of(
            SUBMITTED, EnumSet.of(CONFIRMED, REJECTED, WITHDRAWN),
            CONFIRMED, EnumSet.of(WITHDRAWN),
            REJECTED, EnumSet.noneOf(DeclarationStatus.class),
            WITHDRAWN, EnumSet.noneOf(DeclarationStatus.class)
    );

    public void assertTransitTo(DeclarationStatus target) {
        if (!LEGAL.get(this).contains(target)) {
            throw new IllegalStateException("非法申报状态迁移: " + this + " → " + target);
        }
    }
}
