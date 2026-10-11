package com.openvpp.dispatch.monitor;

import com.openvpp.market.charge.DrDirection;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 监测告警 —— 执行监测的异常记录与人工确认载体（第 55 篇底座）。
 *
 * 记录五列齐备（3.5.2"遥测与告警"对象）：类型、方向、偏差、样点证据、状态。
 * <ul>
 *   <li>方向：事件调节方向；失联告警同样携带（失联发生在某方向的响应执行中）；</li>
 *   <li>偏差：有符号偏差（实测 − 目标）随告警留痕；失联告警无偏差（null）；</li>
 *   <li>样点证据：触发告警的最不利样点（{@link AlertEvidence}）；失联无样点为 null；</li>
 *   <li>状态：OPEN → CONFIRMED 单向前进，确认幂等（首条记录为准），
 *       确认人/时间是审计证据。</li>
 * </ul>
 *
 * 证据刷新：OPEN 期间重复判定取更不利者（超差更多）刷新偏差与证据；
 * 确认后冻结 —— 人工确认的记录不被后续重评改写。
 */
public final class MonitorAlert {

    private final String alertId;
    private final MonitorAlertType type;
    private final String eventId;
    private final String organizerTenantId;
    private final String stationId;
    private final String resourceId;
    private final String commandNo;
    private final DrDirection direction;
    private final DeviationBasis basis;
    private final double toleranceKw;
    private final LocalDateTime raisedAt;

    private AlertState state = AlertState.OPEN;
    /** 有符号偏差（kW，实测 − 目标）；失联告警为 NaN（无偏差口径） */
    private double deviationKw;
    /** 触发告警的最不利样点；失联告警为 null */
    private AlertEvidence evidence;
    /** 内部比较用：方向性超差量（kW，非负），取最大者刷新 */
    private double excessKw;

    private String confirmedBy;
    private LocalDateTime confirmedAt;

    MonitorAlert(String alertId, MonitorAlertType type,
                 String eventId, String organizerTenantId,
                 String stationId, String resourceId, String commandNo,
                 DrDirection direction, DeviationBasis basis,
                 double deviationKw, double excessKw, AlertEvidence evidence,
                 double toleranceKw, LocalDateTime raisedAt) {
        this.alertId = Objects.requireNonNull(alertId, "告警标识不能为空");
        this.type = Objects.requireNonNull(type, "告警类型不能为空");
        this.eventId = Objects.requireNonNull(eventId, "事件标识不能为空");
        this.organizerTenantId = Objects.requireNonNull(organizerTenantId, "组织方租户不能为空");
        this.stationId = Objects.requireNonNull(stationId, "场站标识不能为空");
        this.resourceId = Objects.requireNonNull(resourceId, "资源标识不能为空");
        this.commandNo = Objects.requireNonNull(commandNo, "指令编号不能为空");
        this.direction = Objects.requireNonNull(direction, "调节方向不能为空");
        this.basis = basis;
        this.deviationKw = deviationKw;
        this.excessKw = excessKw;
        this.evidence = evidence;
        this.toleranceKw = toleranceKw;
        this.raisedAt = Objects.requireNonNull(raisedAt, "产生时刻不能为空");
    }

    /** OPEN 期间以更不利判定刷新偏差与证据（确认后冻结，不被重评改写） */
    void refreshIfWorse(double deviationKw, double excessKw, AlertEvidence evidence) {
        if (state != AlertState.OPEN) {
            return;
        }
        if (excessKw > this.excessKw) {
            this.excessKw = excessKw;
            this.deviationKw = deviationKw;
            this.evidence = evidence;
        }
    }

    /**
     * 人工确认：OPEN → CONFIRMED，记录确认人/时间。
     *
     * @return true=本次完成状态迁移；false=已确认过，幂等返回（首条记录为准）
     */
    boolean confirm(String confirmer, LocalDateTime at) {
        if (state == AlertState.CONFIRMED) {
            return false;
        }
        this.confirmedBy = Objects.requireNonNull(confirmer, "确认人不能为空");
        this.confirmedAt = Objects.requireNonNull(at, "确认时刻不能为空");
        this.state = AlertState.CONFIRMED;
        return true;
    }

    public String getAlertId() {
        return alertId;
    }

    public MonitorAlertType getType() {
        return type;
    }

    public String getEventId() {
        return eventId;
    }

    /** 组织方租户 —— 数据范围锚定键 */
    public String getOrganizerTenantId() {
        return organizerTenantId;
    }

    public String getStationId() {
        return stationId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getCommandNo() {
        return commandNo;
    }

    public DrDirection getDirection() {
        return direction;
    }

    /** 判定基准；失联告警为 null */
    public DeviationBasis getBasis() {
        return basis;
    }

    /** 有符号偏差（kW，实测 − 目标）；失联告警为 NaN */
    public double getDeviationKw() {
        return deviationKw;
    }

    /** 触发告警的最不利样点证据；失联告警为 null */
    public AlertEvidence getEvidence() {
        return evidence;
    }

    public double getToleranceKw() {
        return toleranceKw;
    }

    public AlertState getState() {
        return state;
    }

    public LocalDateTime getRaisedAt() {
        return raisedAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    @Override
    public String toString() {
        return "MonitorAlert{id=" + alertId
                + ", type=" + type
                + ", station=" + stationId
                + ", resource=" + resourceId
                + ", commandNo=" + commandNo
                + ", direction=" + direction
                + ", deviationKw=" + deviationKw
                + ", state=" + state
                + '}';
    }
}
