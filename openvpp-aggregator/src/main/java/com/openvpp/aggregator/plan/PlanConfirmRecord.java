package com.openvpp.aggregator.plan;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 人工确认记录 —— 分级计划下发前的人工把关留痕（第 54 篇底座）。
 *
 * 与计划版本一一对应：确认的是"哪个计划的第几个版本"，记录确认人、确认时刻、
 * 被确认版本。重复确认幂等 —— 首条记录为准，后续重复确认不覆盖
 * （确认人/时刻是证据，不是最新值字段）。
 */
public final class PlanConfirmRecord {

    private final String confirmer;
    private final LocalDateTime confirmedAt;
    private final int planVersion;

    public PlanConfirmRecord(String confirmer, LocalDateTime confirmedAt, int planVersion) {
        if (confirmer == null || confirmer.isBlank()) {
            throw new IllegalArgumentException("确认人不能为空");
        }
        this.confirmer = confirmer;
        this.confirmedAt = Objects.requireNonNull(confirmedAt, "确认时刻不能为空");
        if (planVersion < 1) {
            throw new IllegalArgumentException("计划版本必须 ≥ 1: " + planVersion);
        }
        this.planVersion = planVersion;
    }

    public String getConfirmer() {
        return confirmer;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    /** 被确认的计划版本（确认随版本留痕，不随修订漂移） */
    public int getPlanVersion() {
        return planVersion;
    }
}
