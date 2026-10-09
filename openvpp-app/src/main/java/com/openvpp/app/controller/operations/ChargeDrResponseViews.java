package com.openvpp.app.controller.operations;

import com.openvpp.market.charge.CapabilitySnapshot;
import com.openvpp.market.charge.ChargeDeclaration;
import com.openvpp.market.charge.ChargeDrEvent;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 充电桩需求响应接口的响应视图装配 —— 领域对象 → 前端契约 Map（camelCase）。
 *
 * 与 OperationsAuditController 的列名归一同一口径：响应字段由本类显式列出，
 * 领域对象不直接对外序列化（避免内部结构随接口暴露漂移）；
 * 枚举一律同时给出 code 与中文标签，时间字段为 ISO-8601 字符串（Jackson jsr310 默认形态）。
 */
final class ChargeDrResponseViews {

    private ChargeDrResponseViews() {
    }

    /** 事件视图：方向 code + 标签、生命周期、版本、授权场站清单与按当前时刻推导的时间状态 */
    static Map<String, Object> event(ChargeDrEvent e, Clock clock) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", e.getEventId());
        body.put("organizerTenantId", e.getOrganizerTenantId());
        body.put("direction", e.getDirection().name());
        body.put("directionLabel", e.getDirection().getLabel());
        body.put("windowStart", e.getWindowStart());
        body.put("windowEnd", e.getWindowEnd());
        body.put("targetAdjustKw", e.getTargetAdjustKw());
        body.put("declareDeadline", e.getDeclareDeadline());
        body.put("lifecycle", e.getLifecycle().name());
        body.put("version", e.getVersion());
        body.put("timeStatus", e.timeStatusAt(LocalDateTime.now(clock)).name());
        body.put("authorizedStationIds", new ArrayList<>(e.authorizedStationIds()));
        body.put("createdAt", e.getCreatedAt());
        body.put("endedAt", e.getEndedAt());
        return body;
    }

    /** 申报视图：快照版本与请求标识随单留痕，状态迁移时间/原因分列 */
    static Map<String, Object> declaration(ChargeDeclaration d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("declarationId", d.getDeclarationId());
        body.put("eventId", d.getEventId());
        body.put("tenantId", d.getTenantId());
        body.put("stationId", d.getStationId());
        body.put("declaredKw", d.getDeclaredKw());
        body.put("snapshotVersion", d.getSnapshotVersion());
        body.put("requestId", d.getRequestId());
        body.put("status", d.getStatus().name());
        body.put("createdAt", d.getCreatedAt());
        body.put("decidedAt", d.getDecidedAt());
        body.put("decisionReason", d.getDecisionReason());
        return body;
    }

    /** 能力快照视图：额定/基线/削峰可信/填谷可信分列，评估版本与会话指纹随快照留痕 */
    static Map<String, Object> snapshot(CapabilitySnapshot s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stationId", s.getStationId());
        body.put("resourceId", s.getResourceId());
        body.put("ratedPowerKw", s.getRatedPowerKw());
        body.put("baselineKw", s.getBaselineKw());
        body.put("crediblePeakKw", s.getCrediblePeakKw());
        body.put("credibleValleyKw", s.getCredibleValleyKw());
        body.put("assessVersion", s.getAssessVersion());
        body.put("assessedAt", s.getAssessedAt());
        body.put("sessionFingerprint", s.getSessionFingerprint());
        return body;
    }
}
