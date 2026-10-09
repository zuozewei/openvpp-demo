package com.openvpp.market.charge;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 场站充电能力快照 —— 申报容量校验的可信依据（第 53 篇底座）。
 *
 * 三列分明，不得混用：
 * 1. 额定功率（ratedPowerKw）：充电桩设备物理上限；
 * 2. 历史基线（baselineKw）：场站常态充电功率，响应量核算的参照侧之一；
 * 3. 可信容量（crediblePeakKw / credibleValleyKw）：评估口径给出的可承诺调节量，
 *    削峰/填谷分列，申报按事件方向取列。
 *
 * 快照携带评估版本与评估时间，并绑定充电会话指纹：
 * 会话变化即整体失效，过期同样失效 —— 两种情形下都不得继续用旧容量承诺。
 */
public final class CapabilitySnapshot {

    private final String stationId;
    private final String resourceId;
    private final BigDecimal ratedPowerKw;
    private final BigDecimal baselineKw;
    private final BigDecimal crediblePeakKw;
    private final BigDecimal credibleValleyKw;
    private final String assessVersion;
    private final LocalDateTime assessedAt;
    private final String sessionFingerprint;

    public CapabilitySnapshot(String stationId, String resourceId,
                              BigDecimal ratedPowerKw, BigDecimal baselineKw,
                              BigDecimal crediblePeakKw, BigDecimal credibleValleyKw,
                              String assessVersion, LocalDateTime assessedAt,
                              String sessionFingerprint) {
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("stationId 不能为空");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId 不能为空");
        }
        if (ratedPowerKw == null || ratedPowerKw.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("额定功率必须为正: " + ratedPowerKw);
        }
        if (baselineKw == null || baselineKw.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("历史基线不得为负: " + baselineKw);
        }
        assertCredibleWithinRated(crediblePeakKw, ratedPowerKw, "削峰可信容量");
        assertCredibleWithinRated(credibleValleyKw, ratedPowerKw, "填谷可信容量");
        if (assessVersion == null || assessVersion.isBlank()) {
            throw new IllegalArgumentException("assessVersion 不能为空");
        }
        Objects.requireNonNull(assessedAt, "assessedAt 不能为空");
        if (sessionFingerprint == null || sessionFingerprint.isBlank()) {
            throw new IllegalArgumentException("sessionFingerprint 不能为空");
        }
        this.stationId = stationId;
        this.resourceId = resourceId;
        this.ratedPowerKw = ratedPowerKw;
        this.baselineKw = baselineKw;
        this.crediblePeakKw = crediblePeakKw;
        this.credibleValleyKw = credibleValleyKw;
        this.assessVersion = assessVersion;
        this.assessedAt = assessedAt;
        this.sessionFingerprint = sessionFingerprint;
    }

    private static void assertCredibleWithinRated(BigDecimal credibleKw, BigDecimal ratedKw, String column) {
        if (credibleKw == null
                || credibleKw.compareTo(BigDecimal.ZERO) < 0
                || credibleKw.compareTo(ratedKw) > 0) {
            throw new IllegalArgumentException(column + "必须在 [0, 额定功率] 区间: " + credibleKw);
        }
    }

    /** 按调节方向取可信容量列 */
    public BigDecimal credibleCapacityFor(DrDirection direction) {
        Objects.requireNonNull(direction, "direction 不能为空");
        return direction == DrDirection.PEAK_SHAVE ? crediblePeakKw : credibleValleyKw;
    }

    /**
     * 过期判定：评估时间 + 有效时长不晚于 at 即过期。
     * 边界口径：有效期首秒可用、末秒起失效（at == assessedAt + ttl 即过期）。
     */
    public boolean isExpiredAt(LocalDateTime at, Duration ttl) {
        Objects.requireNonNull(at, "at 不能为空");
        Objects.requireNonNull(ttl, "ttl 不能为空");
        return !at.isBefore(assessedAt.plus(ttl));
    }

    public String getStationId() {
        return stationId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public BigDecimal getRatedPowerKw() {
        return ratedPowerKw;
    }

    public BigDecimal getBaselineKw() {
        return baselineKw;
    }

    public BigDecimal getCrediblePeakKw() {
        return crediblePeakKw;
    }

    public BigDecimal getCredibleValleyKw() {
        return credibleValleyKw;
    }

    public String getAssessVersion() {
        return assessVersion;
    }

    public LocalDateTime getAssessedAt() {
        return assessedAt;
    }

    public String getSessionFingerprint() {
        return sessionFingerprint;
    }
}
