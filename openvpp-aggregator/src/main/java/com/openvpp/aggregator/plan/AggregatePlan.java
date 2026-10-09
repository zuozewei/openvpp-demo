package com.openvpp.aggregator.plan;

import com.openvpp.market.charge.DrDirection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 分级计划 —— 充电桩需求响应派单的分级计划实体（第 54 篇底座，规划类 AggregatePlan）。
 *
 * 一棵树两级节点：顶层计划（{@link PlanLevel#PLATFORM_TO_OPERATOR}）把事件目标切成
 * 运营商份额，每个份额派生一个子计划（{@link PlanLevel#OPERATOR_TO_PILE}，
 * 以 parentPlanId 关联）把份额切到单桩并换算绝对目标功率。
 *
 * 不变式（构造即校验，违反即拒绝出生）：
 * <ol>
 *   <li>守恒：Σ分配行调节量 + 缺口 = 本计划目标调节量；</li>
 *   <li>口径分列：调节量（adjustKw）与绝对目标功率（行级 targetPowerKw）永不混写 ——
 *       二级行的目标功率必须等于 {@link DirectionalTargetConverter} 按方向的换算结果，
 *       一级行不得携带目标功率；</li>
 *   <li>版本不覆盖：修订产生新版本对象，已发送版本的证据（确认/发送/回执时标）
 *       随旧版本原样保留，新版本证据列重新置空。</li>
 * </ol>
 *
 * 证据链口径（"下发证据"格：确认、发送、回执、达标分列，无证据不标完成）：
 * 计划层留确认/发送/回执三列；回执 ≠ 达标 —— 功率到位由后续监测工序按遥测判定，
 * 本实体不承载达标结论。
 */
public class AggregatePlan {

    private final String planId;
    private final String parentPlanId;
    private final String eventId;
    private final String organizerTenantId;
    private final PlanLevel level;
    private final int version;
    private final DrDirection direction;
    /** 本计划承担的目标调节量（kW；顶层 = 事件目标，子计划 = 上级份额） */
    private final BigDecimal adjustKw;
    /** 本计划基线合计（kW）—— 响应量核算参照，与调节量分列 */
    private final BigDecimal baselineKw;
    /** 分配缺口（kW）：Σ行 + 缺口 = 目标；&gt; 0 即申报不足，确认与下发被阻止 */
    private final BigDecimal gapKw;
    private final List<AllocationLine> lines;
    private final String createdBy;
    private final LocalDateTime createdAt;

    /** 人工确认记录（确认人/时间/版本；未确认时为 null） */
    private PlanConfirmRecord confirmation;
    /** 发送时标（本版本计划进入下发的时刻；幂等，不覆盖） */
    private LocalDateTime sentAt;
    /** 回执时标（本版本计划收到指令回执的时刻；幂等，不覆盖） */
    private LocalDateTime ackedAt;

    /**
     * 构建一个计划版本。正常入口为 {@link AggregatePlanService} 与
     * {@link #revise}；直接构造须自行保证守恒与层级口径。
     */
    public AggregatePlan(String planId, String parentPlanId, String eventId, String organizerTenantId,
                         PlanLevel level, int version, DrDirection direction,
                         BigDecimal adjustKw, BigDecimal baselineKw, BigDecimal gapKw,
                         List<AllocationLine> lines, String createdBy, LocalDateTime createdAt) {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("计划标识不能为空");
        }
        if (level == null) {
            throw new IllegalArgumentException("分配层级不能为空");
        }
        if (level == PlanLevel.OPERATOR_TO_PILE && (parentPlanId == null || parentPlanId.isBlank())) {
            throw new IllegalArgumentException("子计划必须关联父计划: " + planId);
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("事件标识不能为空");
        }
        if (organizerTenantId == null || organizerTenantId.isBlank()) {
            throw new IllegalArgumentException("组织方租户不能为空");
        }
        this.planId = planId;
        this.parentPlanId = parentPlanId;
        this.eventId = eventId;
        this.organizerTenantId = organizerTenantId;
        this.level = level;
        if (version < 1) {
            throw new IllegalArgumentException("计划版本必须 ≥ 1: " + version);
        }
        this.version = version;
        this.direction = Objects.requireNonNull(direction, "调节方向不能为空");
        if (adjustKw == null || adjustKw.signum() < 0) {
            throw new IllegalArgumentException("目标调节量不得为负: " + adjustKw);
        }
        this.adjustKw = adjustKw;
        if (baselineKw == null || baselineKw.signum() < 0) {
            throw new IllegalArgumentException("基线合计不得为负: " + baselineKw);
        }
        this.baselineKw = baselineKw;
        if (gapKw == null || gapKw.signum() < 0) {
            throw new IllegalArgumentException("分配缺口不得为负: " + gapKw);
        }
        this.gapKw = gapKw;
        if (lines == null) {
            throw new IllegalArgumentException("分配行清单不能为空引用");
        }
        assertLinesMatchLevel(level, direction, lines);
        BigDecimal allocated = lines.stream()
                .map(AllocationLine::getAdjustKw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.add(gapKw).compareTo(adjustKw) != 0) {
            throw new IllegalStateException("计划分配不守恒: 各行合计 " + allocated + " kW + 缺口 " + gapKw
                    + " kW ≠ 目标 " + adjustKw + " kW");
        }
        this.lines = List.copyOf(lines);
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("创建人不能为空");
        }
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(createdAt, "创建时刻不能为空");
    }

    /**
     * 层级口径校验：一级行不得携带目标功率（不混写），二级行必须携带且
     * 等于方向换算结果（换算归属唯一入口，防手写漂移）。
     */
    private static void assertLinesMatchLevel(PlanLevel level, DrDirection direction, List<AllocationLine> lines) {
        for (AllocationLine line : lines) {
            if (level == PlanLevel.PLATFORM_TO_OPERATOR) {
                if (line.getTargetPowerKw() != null || line.getSnapshotVersion() != null) {
                    throw new IllegalStateException("一级分配行不得携带绝对目标功率/快照版本（功率目标与调节量不混写）: "
                            + line.getSubjectId());
                }
            } else {
                if (line.getTargetPowerKw() == null) {
                    throw new IllegalStateException("二级分配行必须携带绝对目标功率: " + line.getSubjectId());
                }
                BigDecimal expected = DirectionalTargetConverter.toTargetPower(
                        direction, line.getBaselineKw(), line.getAdjustKw());
                if (expected.compareTo(line.getTargetPowerKw()) != 0) {
                    throw new IllegalStateException("二级行目标功率与方向换算结果不符: " + line.getSubjectId()
                            + "，行内 " + line.getTargetPowerKw() + " kW，换算应为 " + expected + " kW");
                }
            }
        }
    }

    /**
     * 人工确认：记录确认人/时间/版本。
     *
     * 缺口处置（申报不足时的 3 选 1 运营路径，素材"平台到运营商分配"行）：
     * <ol>
     *   <li>拒绝事件：事件结束，不进入响应；</li>
     *   <li>补申报：场站补充申报后重新分配（重新 buildPlan 产生新计划）；</li>
     *   <li>降目标：平台修订事件目标并按新目标修订计划。</li>
     * </ol>
     * 三条路径都必须先消除缺口 —— 本计划（或整树）缺口 &gt; 0 时确认被拒绝，
     * 部分分配成功不写成总目标可兑现。
     *
     * 幂等：已确认的计划重复确认直接返回，首条确认记录为准（确认人/时间是证据）。
     */
    public void confirm(String confirmer, LocalDateTime at) {
        if (gapKw.signum() > 0) {
            throw new IllegalStateException("计划 " + planId + " v" + version + " 存在分配缺口 " + gapKw
                    + " kW（申报不足），禁止确认与下发 —— 运营路径三选一：拒绝事件 / 补申报后重新分配 / 降目标修订计划");
        }
        if (confirmation != null) {
            return;
        }
        confirmation = new PlanConfirmRecord(confirmer, at, version);
    }

    /**
     * 记录发送时标：要求已人工确认；重复记录不覆盖首条（已发送指令的证据不可改写）。
     */
    public void markSent(LocalDateTime at) {
        if (confirmation == null) {
            throw new IllegalStateException("计划 " + planId + " v" + version + " 未经人工确认，禁止下发");
        }
        Objects.requireNonNull(at, "发送时刻不能为空");
        if (sentAt == null) {
            sentAt = at;
        }
    }

    /**
     * 记录回执时标：要求已发送；重复记录不覆盖首条。
     * 回执只证明设备受理，不证明功率到位 —— 达标判定留给后续监测工序。
     */
    public void recordReceipt(LocalDateTime at) {
        if (sentAt == null) {
            throw new IllegalStateException("计划 " + planId + " v" + version + " 尚未发送，无回执可记");
        }
        Objects.requireNonNull(at, "回执时刻不能为空");
        if (ackedAt == null) {
            ackedAt = at;
        }
    }

    /**
     * 修订计划：以新口径产生新版本，本对象（旧版本）全部证据原样保留。
     *
     * @param revisedBy   修订人
     * @param adjustKw    新目标调节量（kW，≥ 0）
     * @param baselineKw  新基线合计（kW，≥ 0）
     * @param gapKw       新缺口（kW，≥ 0；Σ新行 + 新缺口 = 新目标）
     * @param newLines    新分配行（层级口径与原计划一致）
     * @param at          修订时刻
     * @return 新版本计划对象（version + 1，证据列全空，未确认）
     */
    public AggregatePlan revise(String revisedBy, BigDecimal adjustKw, BigDecimal baselineKw,
                                BigDecimal gapKw, List<AllocationLine> newLines, LocalDateTime at) {
        return new AggregatePlan(planId, parentPlanId, eventId, organizerTenantId, level,
                version + 1, direction, adjustKw, baselineKw, gapKw, newLines, revisedBy, at);
    }

    public String getPlanId() {
        return planId;
    }

    /** 父计划标识（顶层计划为 null） */
    public String getParentPlanId() {
        return parentPlanId;
    }

    public String getEventId() {
        return eventId;
    }

    /** 平台组织方租户 —— 数据范围锚定键 */
    public String getOrganizerTenantId() {
        return organizerTenantId;
    }

    public PlanLevel getLevel() {
        return level;
    }

    public int getVersion() {
        return version;
    }

    public DrDirection getDirection() {
        return direction;
    }

    public BigDecimal getAdjustKw() {
        return adjustKw;
    }

    public BigDecimal getBaselineKw() {
        return baselineKw;
    }

    public BigDecimal getGapKw() {
        return gapKw;
    }

    public List<AllocationLine> getLines() {
        return lines;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public PlanConfirmRecord getConfirmation() {
        return confirmation;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public LocalDateTime getAckedAt() {
        return ackedAt;
    }

    /** 本版本是否已可下发：无缺口且已人工确认 */
    public boolean isDispatchable() {
        return gapKw.signum() == 0 && confirmation != null;
    }
}
