package com.openvpp.aggregator.plan;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 分配行 —— 分级计划的一条分配记录（第 54 篇底座）。
 *
 * 字段语义随 {@link PlanLevel} 分化，构造即定型：
 * <ul>
 *   <li>一级行（平台→运营商）：subjectId = 运营商租户标识，stationId / snapshotVersion /
 *       targetPowerKw 为 null，只留调节量与基线合计；</li>
 *   <li>二级行（运营商→桩）：subjectId = 桩资源标识，stationId / snapshotVersion /
 *       targetPowerKw 必填 —— 目标功率由 {@link DirectionalTargetConverter} 换算，
 *       与调节量分列，不混写。</li>
 * </ul>
 *
 * 不变式：adjustKw ≥ 0；baselineKw ≥ 0；行内调节量不得超过本行有效上限
 * （上限由 {@link ConstraintAllocator} 分配时保证，行对象不再重复校验）。
 */
public final class AllocationLine {

    private final String subjectId;
    private final String stationId;
    private final BigDecimal adjustKw;
    private final BigDecimal baselineKw;
    private final BigDecimal targetPowerKw;
    private final String snapshotVersion;

    /**
     * 一级分配行（平台→运营商）。
     *
     * @param operatorTenantId 运营商租户标识
     * @param adjustKw         分得的调节量（kW）
     * @param baselineKw       该运营商下属场站的基线合计（kW）
     */
    public static AllocationLine operatorLine(String operatorTenantId, BigDecimal adjustKw, BigDecimal baselineKw) {
        if (operatorTenantId == null || operatorTenantId.isBlank()) {
            throw new IllegalArgumentException("运营商租户标识不能为空");
        }
        assertAdjust(adjustKw);
        assertBaseline(baselineKw);
        return new AllocationLine(operatorTenantId, null, adjustKw, baselineKw, null, null);
    }

    /**
     * 二级分配行（运营商→桩）。
     *
     * @param resourceId      桩资源标识
     * @param stationId       归属场站
     * @param adjustKw        分得的调节量（kW）
     * @param baselineKw      该桩历史基线（kW）
     * @param targetPowerKw   绝对目标功率（kW，已由方向换算产出，非调节量）
     * @param snapshotVersion 能力快照版本（留痕）
     */
    public static AllocationLine pileLine(String resourceId, String stationId, BigDecimal adjustKw,
                                          BigDecimal baselineKw, BigDecimal targetPowerKw, String snapshotVersion) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("桩资源标识不能为空");
        }
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("归属场站不能为空");
        }
        if (targetPowerKw == null || targetPowerKw.signum() < 0) {
            throw new IllegalArgumentException("绝对目标功率不得为负（零目标合法）: " + targetPowerKw);
        }
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("能力快照版本不能为空");
        }
        assertAdjust(adjustKw);
        assertBaseline(baselineKw);
        return new AllocationLine(resourceId, stationId, adjustKw, baselineKw, targetPowerKw, snapshotVersion);
    }

    private AllocationLine(String subjectId, String stationId, BigDecimal adjustKw, BigDecimal baselineKw,
                           BigDecimal targetPowerKw, String snapshotVersion) {
        this.subjectId = subjectId;
        this.stationId = stationId;
        this.adjustKw = adjustKw;
        this.baselineKw = baselineKw;
        this.targetPowerKw = targetPowerKw;
        this.snapshotVersion = snapshotVersion;
    }

    private static void assertAdjust(BigDecimal adjustKw) {
        if (adjustKw == null || adjustKw.signum() < 0) {
            throw new IllegalArgumentException("分配调节量不得为负: " + adjustKw);
        }
    }

    private static void assertBaseline(BigDecimal baselineKw) {
        if (baselineKw == null || baselineKw.signum() < 0) {
            throw new IllegalArgumentException("基线不得为负: " + baselineKw);
        }
    }

    /** 分配对象：一级行 = 运营商租户标识；二级行 = 桩资源标识 */
    public String getSubjectId() {
        return subjectId;
    }

    /** 归属场站（一级行为 null） */
    public String getStationId() {
        return stationId;
    }

    /** 分得的调节量（kW） */
    public BigDecimal getAdjustKw() {
        return adjustKw;
    }

    /** 历史基线（kW）：一级行为下属场站合计，二级行为该桩基线 */
    public BigDecimal getBaselineKw() {
        return baselineKw;
    }

    /** 绝对目标功率（kW，仅二级行携带；与调节量分列，不混用） */
    public BigDecimal getTargetPowerKw() {
        return targetPowerKw;
    }

    /** 能力快照版本（仅二级行携带，留痕） */
    public String getSnapshotVersion() {
        return snapshotVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AllocationLine)) {
            return false;
        }
        AllocationLine that = (AllocationLine) o;
        return Objects.equals(subjectId, that.subjectId)
                && Objects.equals(stationId, that.stationId)
                && adjustKw.compareTo(that.adjustKw) == 0
                && baselineKw.compareTo(that.baselineKw) == 0
                && (targetPowerKw == null ? that.targetPowerKw == null
                        : that.targetPowerKw != null && targetPowerKw.compareTo(that.targetPowerKw) == 0)
                && Objects.equals(snapshotVersion, that.snapshotVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectId, stationId, adjustKw, baselineKw, targetPowerKw, snapshotVersion);
    }

    @Override
    public String toString() {
        return "AllocationLine{subject=" + subjectId
                + (stationId == null ? "" : ", station=" + stationId)
                + ", adjustKw=" + adjustKw
                + ", baselineKw=" + baselineKw
                + (targetPowerKw == null ? "" : ", targetPowerKw=" + targetPowerKw)
                + (snapshotVersion == null ? "" : ", snapshot=" + snapshotVersion)
                + '}';
    }
}
