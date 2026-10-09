package com.openvpp.market.charge;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 事件参与授权记录 —— 平台共享事件的显式授权凭证（一条 = 一个场站获得申报资格）。
 *
 * 可见性边界：授权只对"事件头"放行（方向/时间窗/目标/截止等事件要素），
 * 不放开关联租户的任何业务数据 —— 各场站的申报、计划、执行明细仍按
 * 各自 DataScope 查询，共享事件不构成跨租户数据通道。
 */
public final class EventParticipation {

    private final String stationId;
    private final String authorizedBy;
    private final LocalDateTime authorizedAt;

    public EventParticipation(String stationId, String authorizedBy, LocalDateTime authorizedAt) {
        this.stationId = Objects.requireNonNull(stationId, "stationId 不能为空");
        this.authorizedBy = Objects.requireNonNull(authorizedBy, "authorizedBy 不能为空");
        this.authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt 不能为空");
    }

    public String getStationId() {
        return stationId;
    }

    public String getAuthorizedBy() {
        return authorizedBy;
    }

    public LocalDateTime getAuthorizedAt() {
        return authorizedAt;
    }
}
