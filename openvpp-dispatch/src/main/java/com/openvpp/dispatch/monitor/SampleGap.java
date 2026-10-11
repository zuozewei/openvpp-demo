package com.openvpp.dispatch.monitor;

/**
 * 样点缺口 —— 遥测流中序号断档的记录（第 55 篇"缺失与有效零值区分"验收口径）。
 *
 * 缺失不补 0：断档只登记为缺口（起止序号 + 前后采样时标边界），曲线在缺口处
 * 留空/打缺失标记，绝不合成功率为 0 的样点 —— 合成零值会把"没数据"伪装成
 * "设备在零功率运行"，方向性判定与响应积分都会被污染。
 *
 * 有效零值是另一回事：设备真实上报的 0 kW 是正常样点（无质量标记），
 * 与缺口在数据面上严格分列。
 */
public final class SampleGap {

    private final String stationId;
    private final String resourceId;
    /** 缺口首个缺失序号（含） */
    private final long fromSeq;
    /** 缺口最后缺失序号（含） */
    private final long toSeq;
    /** 缺口前最后一条实样的采样时标；缺口在流首时为 -1 */
    private final long afterSampledAtMs;
    /** 缺口后首条实样的采样时标 */
    private final long beforeSampledAtMs;

    SampleGap(String stationId, String resourceId, long fromSeq, long toSeq,
              long afterSampledAtMs, long beforeSampledAtMs) {
        if (fromSeq > toSeq) {
            throw new IllegalArgumentException("缺口序号范围非法: " + fromSeq + " > " + toSeq);
        }
        this.stationId = stationId;
        this.resourceId = resourceId;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
        this.afterSampledAtMs = afterSampledAtMs;
        this.beforeSampledAtMs = beforeSampledAtMs;
    }

    /** 缺失序号条数 */
    public long getMissingCount() {
        return toSeq - fromSeq + 1;
    }

    public String getStationId() {
        return stationId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public long getFromSeq() {
        return fromSeq;
    }

    public long getToSeq() {
        return toSeq;
    }

    public long getAfterSampledAtMs() {
        return afterSampledAtMs;
    }

    public long getBeforeSampledAtMs() {
        return beforeSampledAtMs;
    }

    @Override
    public String toString() {
        return "SampleGap{station=" + stationId
                + ", resource=" + resourceId
                + ", seq=[" + fromSeq + ".." + toSeq + "]"
                + ", afterSampledAtMs=" + afterSampledAtMs
                + ", beforeSampledAtMs=" + beforeSampledAtMs
                + '}';
    }
}
