package com.openvpp.settlement.assess;

import com.openvpp.market.charge.DrDirection;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 基线冻结记录 —— 响应开始前对核算参照系的锁定（第 56 篇底座，规划类 BaselineFreeze）。
 *
 * 与既有"能力快照落库"（market 域 CapabilitySnapshot）刻意分开：快照是申报阶段的
 * 容量承诺凭证，随会话指纹与有效期失效；冻结是评估阶段的核算参照，一旦生成即
 * 成为只读版本 —— 锁后拒写，响应后的评估只读本记录。
 *
 * 记录五要素齐备（3.5.2"基线快照与评估"对象）：冻结时刻、样本来源、规则版本、
 * 基线版本、冻结样本曲线，缺一不可：
 * <ul>
 *   <li>frozenAt 必须早于 windowStart（响应前冻结质量门，登记簿强制）；</li>
 *   <li>sampleSource 明示样本来源（如已确认申报基线 / 相似日均值 / 指令前基线样点），
 *       争议复核时按来源重算；</li>
 *   <li>ruleVersion / baselineVersion 随记录留痕，规则升级只影响新事件，不回溯改写。</li>
 * </ul>
 *
 * 不可变值对象： equals 覆盖全字段，供登记簿"同键同内容幂等返回、改数拒写"判定。
 */
public final class BaselineFreeze {

    private final String eventId;
    private final String organizerTenantId;
    private final String stationId;
    private final String resourceId;
    private final DrDirection direction;
    private final LocalDateTime frozenAt;
    private final LocalDateTime windowStart;
    private final LocalDateTime windowEnd;
    private final String sampleSource;
    private final String ruleVersion;
    private final String baselineVersion;
    private final List<FrozenBaselinePoint> points;

    public BaselineFreeze(String eventId, String organizerTenantId, String stationId,
                          String resourceId, DrDirection direction,
                          LocalDateTime frozenAt, LocalDateTime windowStart, LocalDateTime windowEnd,
                          String sampleSource, String ruleVersion, String baselineVersion,
                          List<FrozenBaselinePoint> points) {
        this.eventId = requireText(eventId, "事件标识");
        this.organizerTenantId = requireText(organizerTenantId, "组织方租户");
        this.stationId = requireText(stationId, "场站标识");
        this.resourceId = requireText(resourceId, "资源标识");
        this.direction = Objects.requireNonNull(direction, "调节方向不能为空");
        this.frozenAt = Objects.requireNonNull(frozenAt, "冻结时刻不能为空");
        this.windowStart = Objects.requireNonNull(windowStart, "响应窗口开始不能为空");
        this.windowEnd = Objects.requireNonNull(windowEnd, "响应窗口结束不能为空");
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("响应窗口开始必须早于结束: " + windowStart + " ~ " + windowEnd);
        }
        this.sampleSource = requireText(sampleSource, "样本来源");
        this.ruleVersion = requireText(ruleVersion, "规则版本");
        this.baselineVersion = requireText(baselineVersion, "基线版本");
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("冻结基线样本不得为空");
        }
        this.points = List.copyOf(points);
    }

    /** 响应窗口开始（epoch ms，系统默认时区）—— 冻结质量门与计量区间的换算基准 */
    public long windowStartMs() {
        return windowStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 响应窗口结束（epoch ms，系统默认时区） */
    public long windowEndMs() {
        return windowEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public String getEventId() {
        return eventId;
    }

    public String getOrganizerTenantId() {
        return organizerTenantId;
    }

    public String getStationId() {
        return stationId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public DrDirection getDirection() {
        return direction;
    }

    public LocalDateTime getFrozenAt() {
        return frozenAt;
    }

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public String getSampleSource() {
        return sampleSource;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public List<FrozenBaselinePoint> getPoints() {
        return points;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaselineFreeze)) {
            return false;
        }
        BaselineFreeze that = (BaselineFreeze) o;
        return eventId.equals(that.eventId)
                && organizerTenantId.equals(that.organizerTenantId)
                && stationId.equals(that.stationId)
                && resourceId.equals(that.resourceId)
                && direction == that.direction
                && frozenAt.equals(that.frozenAt)
                && windowStart.equals(that.windowStart)
                && windowEnd.equals(that.windowEnd)
                && sampleSource.equals(that.sampleSource)
                && ruleVersion.equals(that.ruleVersion)
                && baselineVersion.equals(that.baselineVersion)
                && points.equals(that.points);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, organizerTenantId, stationId, resourceId, direction,
                frozenAt, windowStart, windowEnd, sampleSource, ruleVersion, baselineVersion, points);
    }

    @Override
    public String toString() {
        return "BaselineFreeze{eventId=" + eventId + ", stationId=" + stationId
                + ", resourceId=" + resourceId + ", direction=" + direction
                + ", frozenAt=" + frozenAt + ", window=" + windowStart + "~" + windowEnd
                + ", sampleSource=" + sampleSource + ", ruleVersion=" + ruleVersion
                + ", baselineVersion=" + baselineVersion + ", points=" + points.size() + "}";
    }
}
