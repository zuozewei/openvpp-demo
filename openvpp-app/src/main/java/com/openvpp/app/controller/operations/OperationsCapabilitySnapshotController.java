package com.openvpp.app.controller.operations;

import com.openvpp.app.auth.AuditLogService;
import com.openvpp.app.auth.AuthService;
import com.openvpp.app.auth.RequireRole;
import com.openvpp.common.context.RoleType;
import com.openvpp.market.charge.CapabilitySnapshot;
import com.openvpp.market.charge.SnapshotRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 能力快照教学入口（3.5.3 接口契约第 4 行配套：平台登记场站能力快照，申报据此校验）。
 *
 * 安全边界：
 * 1. 角色 —— 仅平台（评估口径由平台侧统一登记，场站不自带容量承诺）；
 * 2. 快照列口径 —— 额定功率/历史基线/削峰可信/填谷可信分列，可信容量须在 [0, 额定] 区间，
 *    由 CapabilitySnapshot 构造校验兜底；
 * 3. 会话指纹 —— 每次登记即成为该场站当前指纹来源：充电会话变化即旧版本快照整体失效，
 *    申报引用旧版本时由 SnapshotRegistry.assertUsable 拒绝（409）；
 * 4. 审计 —— 每次登记留痕（场站/版本/结果）。
 *
 * 演示数据：启动时 ChargeDemoFixture 已为两个演示场站登记 demo-v1 版本，
 * 本入口用于登记后续评估版本（新指纹、新版本号），演示"会话变化 → 旧版本失效"链路。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}/operations")
public class OperationsCapabilitySnapshotController {

    public static final String ACTION_SNAPSHOT_REGISTER = "SNAPSHOT_REGISTER";
    public static final String TARGET_SNAPSHOT = "CAPABILITY_SNAPSHOT";
    public static final String RESULT_SUCCESS = AuthService.RESULT_SUCCESS;
    public static final String RESULT_REJECTED = AuthService.RESULT_REJECTED;

    private final SnapshotRegistry snapshotRegistry;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OperationsCapabilitySnapshotController(SnapshotRegistry snapshotRegistry,
                                                  AuditLogService auditLogService,
                                                  Clock clock) {
        this.snapshotRegistry = snapshotRegistry;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /** 登记能力快照：同场站同版本重复登记即拒（409，版本不可覆盖） */
    @RequireRole(RoleType.PLATFORM_ADMIN)
    @PostMapping("/capability-snapshots")
    public Map<String, Object> register(@RequestBody RegisterSnapshotRequest request) {
        String stationId = requireText("stationId", request.getStationId());
        try {
            CapabilitySnapshot snapshot = snapshotRegistry.register(new CapabilitySnapshot(
                    stationId,
                    requireText("resourceId", request.getResourceId()),
                    requirePositiveKw("ratedPowerKw", request.getRatedPowerKw()),
                    requireNonNegativeKw("baselineKw", request.getBaselineKw()),
                    requireNonNegativeKw("crediblePeakKw", request.getCrediblePeakKw()),
                    requireNonNegativeKw("credibleValleyKw", request.getCredibleValleyKw()),
                    requireText("assessVersion", request.getAssessVersion()),
                    request.getAssessedAt() == null ? LocalDateTime.now(clock) : request.getAssessedAt(),
                    requireText("sessionFingerprint", request.getSessionFingerprint())));
            auditLogService.recordForCurrent(ACTION_SNAPSHOT_REGISTER, TARGET_SNAPSHOT,
                    stationId + "/" + snapshot.getAssessVersion(), "-",
                    RESULT_SUCCESS, "能力快照登记");
            return ChargeDrResponseViews.snapshot(snapshot);
        } catch (IllegalArgumentException | IllegalStateException e) {
            auditLogService.recordForCurrent(ACTION_SNAPSHOT_REGISTER, TARGET_SNAPSHOT,
                    stationId, "-", RESULT_REJECTED, e.getMessage());
            throw e;
        }
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static BigDecimal requirePositiveKw(String field, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " 必须为正: " + value);
        }
        return value;
    }

    private static BigDecimal requireNonNegativeKw(String field, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " 不得为负: " + value);
        }
        return value;
    }

    /** 登记快照请求体：四列容量 + 评估版本 + 会话指纹必填，评估时间可选（缺省取当前时刻） */
    public static final class RegisterSnapshotRequest {
        private String stationId;
        private String resourceId;
        private BigDecimal ratedPowerKw;
        private BigDecimal baselineKw;
        private BigDecimal crediblePeakKw;
        private BigDecimal credibleValleyKw;
        private String assessVersion;
        private LocalDateTime assessedAt;
        private String sessionFingerprint;

        public String getStationId() {
            return stationId;
        }

        public void setStationId(String stationId) {
            this.stationId = stationId;
        }

        public String getResourceId() {
            return resourceId;
        }

        public void setResourceId(String resourceId) {
            this.resourceId = resourceId;
        }

        public BigDecimal getRatedPowerKw() {
            return ratedPowerKw;
        }

        public void setRatedPowerKw(BigDecimal ratedPowerKw) {
            this.ratedPowerKw = ratedPowerKw;
        }

        public BigDecimal getBaselineKw() {
            return baselineKw;
        }

        public void setBaselineKw(BigDecimal baselineKw) {
            this.baselineKw = baselineKw;
        }

        public BigDecimal getCrediblePeakKw() {
            return crediblePeakKw;
        }

        public void setCrediblePeakKw(BigDecimal crediblePeakKw) {
            this.crediblePeakKw = crediblePeakKw;
        }

        public BigDecimal getCredibleValleyKw() {
            return credibleValleyKw;
        }

        public void setCredibleValleyKw(BigDecimal credibleValleyKw) {
            this.credibleValleyKw = credibleValleyKw;
        }

        public String getAssessVersion() {
            return assessVersion;
        }

        public void setAssessVersion(String assessVersion) {
            this.assessVersion = assessVersion;
        }

        public LocalDateTime getAssessedAt() {
            return assessedAt;
        }

        public void setAssessedAt(LocalDateTime assessedAt) {
            this.assessedAt = assessedAt;
        }

        public String getSessionFingerprint() {
            return sessionFingerprint;
        }

        public void setSessionFingerprint(String sessionFingerprint) {
            this.sessionFingerprint = sessionFingerprint;
        }
    }
}
