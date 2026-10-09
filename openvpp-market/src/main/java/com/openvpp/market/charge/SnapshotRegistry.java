package com.openvpp.market.charge;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 能力快照登记簿 —— 申报时快照有效性判定的唯一权威入口（第 53 篇底座）。
 *
 * 规则：
 * 1. 每个场站的"当前会话指纹"以最近一次登记为准 —— 充电会话一旦变化，
 *    旧版本快照全部失效，不得继续用旧容量承诺；
 * 2. 过期判定按快照自身评估时间 + 登记簿统一有效期（ttl）计算；
 * 3. assertUsable 按序校验：版本已登记 → 未过期 → 会话指纹未变化，
 *    任一不满足即拒绝，返回的可用快照供申报取方向可信容量。
 *
 * 教学内存版：单 JVM 内登记与判定即时生效；分布式部署时快照与指纹需外置存储，口径不变。
 */
public class SnapshotRegistry {

    private final Duration ttl;
    private final Map<String, Map<String, CapabilitySnapshot>> byStation = new LinkedHashMap<>();
    private final Map<String, String> currentFingerprintByStation = new LinkedHashMap<>();

    public SnapshotRegistry(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("快照有效期必须为正: " + ttl);
        }
        this.ttl = ttl;
    }

    /** 登记快照：同场站同版本重复登记即拒；新登记即成为该场站当前指纹来源 */
    public synchronized CapabilitySnapshot register(CapabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "快照不能为空");
        Map<String, CapabilitySnapshot> versions = byStation.computeIfAbsent(snapshot.getStationId(), k -> new LinkedHashMap<>());
        if (versions.putIfAbsent(snapshot.getAssessVersion(), snapshot) != null) {
            throw new IllegalStateException("能力快照版本已登记: " + snapshot.getStationId()
                    + " / " + snapshot.getAssessVersion());
        }
        currentFingerprintByStation.put(snapshot.getStationId(), snapshot.getSessionFingerprint());
        return snapshot;
    }

    /** 按场站 + 评估版本取快照；未登记抛异常 */
    public synchronized CapabilitySnapshot require(String stationId, String assessVersion) {
        CapabilitySnapshot snapshot = byStation.getOrDefault(stationId, Map.of()).get(assessVersion);
        if (snapshot == null) {
            throw new IllegalArgumentException("未登记能力快照: " + stationId + " / " + assessVersion);
        }
        return snapshot;
    }

    /** 场站当前快照：按登记顺序取最近一次登记的版本 */
    public synchronized CapabilitySnapshot currentOf(String stationId) {
        Map<String, CapabilitySnapshot> versions = byStation.get(stationId);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("未登记能力快照: " + stationId);
        }
        CapabilitySnapshot latest = null;
        for (CapabilitySnapshot snapshot : versions.values()) {
            latest = snapshot;
        }
        return latest;
    }

    /** 场站当前会话指纹；未登记返回 null */
    public synchronized String currentFingerprintOf(String stationId) {
        return currentFingerprintByStation.get(stationId);
    }

    public Duration getTtl() {
        return ttl;
    }

    public boolean isExpired(CapabilitySnapshot snapshot, LocalDateTime at) {
        return snapshot.isExpiredAt(at, ttl);
    }

    /**
     * 申报前置判定：快照必须已登记、未过期、且会话指纹未变化。
     *
     * @return 可用快照（调用方按事件方向取可信容量列）
     * @throws IllegalArgumentException 快照版本未登记
     * @throws IllegalStateException    快照已过期 / 充电会话已变化导致旧版本失效
     */
    public CapabilitySnapshot assertUsable(String stationId, String assessVersion, LocalDateTime at) {
        CapabilitySnapshot snapshot = require(stationId, assessVersion);
        if (isExpired(snapshot, at)) {
            throw new IllegalStateException("能力快照已过期: " + stationId + " / " + assessVersion
                    + "，评估时间 " + snapshot.getAssessedAt() + "，有效期 " + ttl.toMinutes() + " 分钟");
        }
        String currentFingerprint = currentFingerprintOf(stationId);
        if (!snapshot.getSessionFingerprint().equals(currentFingerprint)) {
            throw new IllegalStateException("充电会话已变化，能力快照版本失效: " + stationId + " / " + assessVersion
                    + "，不得继续用旧容量承诺");
        }
        return snapshot;
    }
}
