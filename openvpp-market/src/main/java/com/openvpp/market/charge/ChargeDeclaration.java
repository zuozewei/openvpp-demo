package com.openvpp.market.charge;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 场站申报单 —— 事件/租户/场站三方关联 + 申报容量 + 能力快照版本 + 提交请求标识（第 53 篇底座）。
 *
 * 不变式：
 * 1. 申报容量引用快照时即按事件方向校验过可信容量，快照版本随单留痕；
 * 2. 状态迁移只走 DeclarationStatus 状态机，终态不可逆；
 * 3. 请求标识（requestId）是幂等去重键，同标识重复提交返回本单、不重复占用。
 */
public class ChargeDeclaration {

    private final String declarationId;
    private final String eventId;
    private final String tenantId;
    private final String stationId;
    private final BigDecimal declaredKw;
    private final String snapshotVersion;
    private final String requestId;
    private final LocalDateTime createdAt;

    private DeclarationStatus status;
    private LocalDateTime decidedAt;
    private String decisionReason;

    public ChargeDeclaration(String declarationId, String eventId, String tenantId, String stationId,
                             BigDecimal declaredKw, String snapshotVersion, String requestId,
                             LocalDateTime createdAt) {
        this.declarationId = declarationId;
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.stationId = stationId;
        this.declaredKw = declaredKw;
        this.snapshotVersion = snapshotVersion;
        this.requestId = requestId;
        this.createdAt = createdAt;
        this.status = DeclarationStatus.SUBMITTED;
    }

    public void transitTo(DeclarationStatus target) {
        status.assertTransitTo(target);
        status = target;
        decidedAt = LocalDateTime.now();
    }

    public void markReason(String reason) {
        this.decisionReason = reason;
    }

    public String getDeclarationId() {
        return declarationId;
    }

    public String getEventId() {
        return eventId;
    }

    /** 场站归属租户 —— 数据范围锚定键 */
    public String getTenantId() {
        return tenantId;
    }

    public String getStationId() {
        return stationId;
    }

    public BigDecimal getDeclaredKw() {
        return declaredKw;
    }

    public String getSnapshotVersion() {
        return snapshotVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public DeclarationStatus getStatus() {
        return status;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }
}
