package com.openvpp.app.orchestration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 园区需求响应贯穿案例的演示结果。
 * 关联标识 responseId 贯穿 任务/指令/基线/账单，供争议核查与追溯。
 */
public class DemoRunResult {

    private String responseId;
    private String path;                 // NORMAL / DEGRADED / DISPUTED / DISPUTE（结算后独立更正）
    private boolean feasible;            // 聚合可行性
    private BigDecimal gapKw = BigDecimal.ZERO;
    private BigDecimal baselineKw;       // 各时段基线均值
    private BigDecimal actualKw;         // 各时段实测均值
    private BigDecimal responseKwh;      // 实际响应电量
    private BigDecimal passRatePct;      // 合格率
    private BigDecimal grossYuan;        // 补偿毛额 = 结算电量 × 单价
    private BigDecimal penaltyYuan = BigDecimal.ZERO;   // 偏差考核扣款
    private BigDecimal settleYuan;       // 平台净实收 = 毛额 − 考核扣款（可分配金额）
    private Map<String, BigDecimal> allocation;   // 用户分摊
    private String correctionVersion;             // 争议更正生成的账期版本（V2/V3…）
    private BigDecimal correctedResponseKwh;      // 更正口径响应量
    private BigDecimal correctedSettleYuan;       // 更正后净实收
    private BigDecimal correctionDiffYuan;        // 与基期净实收的差额（可正可负）
    private List<String> trace;          // 关键步骤日志（手工核算底稿对照）
    private boolean idempotentReplay;    // 本次是否为幂等重放（未重复执行/出账）

    public String getResponseId() { return responseId; }
    public void setResponseId(String v) { this.responseId = v; }
    public String getPath() { return path; }
    public void setPath(String v) { this.path = v; }
    public boolean isFeasible() { return feasible; }
    public void setFeasible(boolean v) { this.feasible = v; }
    public BigDecimal getGapKw() { return gapKw; }
    public void setGapKw(BigDecimal v) { this.gapKw = v; }
    public BigDecimal getBaselineKw() { return baselineKw; }
    public void setBaselineKw(BigDecimal v) { this.baselineKw = v; }
    public BigDecimal getActualKw() { return actualKw; }
    public void setActualKw(BigDecimal v) { this.actualKw = v; }
    public BigDecimal getResponseKwh() { return responseKwh; }
    public void setResponseKwh(BigDecimal v) { this.responseKwh = v; }
    public BigDecimal getPassRatePct() { return passRatePct; }
    public void setPassRatePct(BigDecimal v) { this.passRatePct = v; }
    public BigDecimal getSettleYuan() { return settleYuan; }
    public void setSettleYuan(BigDecimal v) { this.settleYuan = v; }
    public BigDecimal getGrossYuan() { return grossYuan; }
    public void setGrossYuan(BigDecimal v) { this.grossYuan = v; }
    public BigDecimal getPenaltyYuan() { return penaltyYuan; }
    public void setPenaltyYuan(BigDecimal v) { this.penaltyYuan = v; }
    public Map<String, BigDecimal> getAllocation() { return allocation; }
    public void setAllocation(Map<String, BigDecimal> v) { this.allocation = v; }
    public String getCorrectionVersion() { return correctionVersion; }
    public void setCorrectionVersion(String v) { this.correctionVersion = v; }
    public BigDecimal getCorrectedResponseKwh() { return correctedResponseKwh; }
    public void setCorrectedResponseKwh(BigDecimal v) { this.correctedResponseKwh = v; }
    public BigDecimal getCorrectedSettleYuan() { return correctedSettleYuan; }
    public void setCorrectedSettleYuan(BigDecimal v) { this.correctedSettleYuan = v; }
    public BigDecimal getCorrectionDiffYuan() { return correctionDiffYuan; }
    public void setCorrectionDiffYuan(BigDecimal v) { this.correctionDiffYuan = v; }
    public List<String> getTrace() { return trace; }
    public void setTrace(List<String> v) { this.trace = v; }
    public boolean isIdempotentReplay() { return idempotentReplay; }
    public void setIdempotentReplay(boolean v) { this.idempotentReplay = v; }
}
