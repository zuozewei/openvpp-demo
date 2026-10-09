package com.openvpp.market.charge;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力快照与登记簿单测（第 53 篇底座）：
 * 三列分明构造校验 / 过期判定边界 / 版本登记唯一性 / 会话指纹变化使旧版本失效。
 */
class SnapshotRegistryTest {

    private static final LocalDateTime ASSESSED_AT = LocalDateTime.of(2026, 10, 8, 12, 0);

    private static CapabilitySnapshot snapshot(String stationId, String version,
                                               String fingerprint, LocalDateTime assessedAt) {
        return new CapabilitySnapshot(stationId, "ev-charge-" + stationId.toLowerCase(),
                new BigDecimal("240"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"),
                version, assessedAt, fingerprint);
    }

    @Test
    void 三列分明且方向取列正确() {
        CapabilitySnapshot snapshot = snapshot("S-11101", "V1", "fp-A", ASSESSED_AT);
        assertEquals(new BigDecimal("240"), snapshot.getRatedPowerKw(), "额定功率列");
        assertEquals(new BigDecimal("120"), snapshot.getBaselineKw(), "历史基线列");
        assertEquals(new BigDecimal("100"), snapshot.credibleCapacityFor(DrDirection.PEAK_SHAVE), "削峰可信容量列");
        assertEquals(new BigDecimal("80"), snapshot.credibleCapacityFor(DrDirection.VALLEY_FILL), "填谷可信容量列");
        assertEquals("V1", snapshot.getAssessVersion());
        assertEquals(ASSESSED_AT, snapshot.getAssessedAt());
        assertEquals("fp-A", snapshot.getSessionFingerprint());
    }

    @Test
    void 快照构造守住院线() {
        // 可信容量超过额定功率
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot("S-11101", "ev-x",
                new BigDecimal("100"), new BigDecimal("60"),
                new BigDecimal("120"), new BigDecimal("80"), "V1", ASSESSED_AT, "fp-A"));
        // 负基线
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot("S-11101", "ev-x",
                new BigDecimal("240"), new BigDecimal("-1"),
                new BigDecimal("100"), new BigDecimal("80"), "V1", ASSESSED_AT, "fp-A"));
        // 评估版本为空
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot("S-11101", "ev-x",
                new BigDecimal("240"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"), " ", ASSESSED_AT, "fp-A"));
    }

    @Test
    void 过期判定含边界() {
        SnapshotRegistry registry = new SnapshotRegistry(Duration.ofMinutes(30));
        registry.register(snapshot("S-11101", "V1", "fp-A", ASSESSED_AT));

        // 12:00 + 30 分钟 = 12:30 失效：12:29:59 可用，12:30:00 过期
        assertSame(registry.require("S-11101", "V1"),
                registry.assertUsable("S-11101", "V1", LocalDateTime.of(2026, 10, 8, 12, 29, 59)));
        LocalDateTime expiredAt = LocalDateTime.of(2026, 10, 8, 12, 30);
        IllegalStateException expired = assertThrows(IllegalStateException.class,
                () -> registry.assertUsable("S-11101", "V1", expiredAt));
        assertTrue(expired.getMessage().contains("过期"));
        assertTrue(registry.require("S-11101", "V1").isExpiredAt(expiredAt, Duration.ofMinutes(30)));
        assertFalse(registry.require("S-11101", "V1").isExpiredAt(expiredAt.minusSeconds(1), Duration.ofMinutes(30)));
    }

    @Test
    void 同场站同版本重复登记拒绝() {
        SnapshotRegistry registry = new SnapshotRegistry(Duration.ofMinutes(30));
        registry.register(snapshot("S-11101", "V1", "fp-A", ASSESSED_AT));
        assertThrows(IllegalStateException.class,
                () -> registry.register(snapshot("S-11101", "V1", "fp-A", ASSESSED_AT)));
        // 不同版本正常登记
        assertDoesNotThrow(() -> registry.register(snapshot("S-11101", "V2", "fp-B",
                LocalDateTime.of(2026, 10, 8, 12, 10))));
    }

    @Test
    void 会话指纹变化旧版本失效当前版本可用() {
        SnapshotRegistry registry = new SnapshotRegistry(Duration.ofMinutes(30));
        registry.register(snapshot("S-11101", "V1", "fp-A", ASSESSED_AT));
        registry.register(snapshot("S-11101", "V2", "fp-B", LocalDateTime.of(2026, 10, 8, 12, 10)));

        assertEquals("fp-B", registry.currentFingerprintOf("S-11101"));
        assertEquals("V2", registry.currentOf("S-11101").getAssessVersion());

        // 旧版本未过期但指纹已变化：assertUsable 拒绝，不得用旧容量承诺
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> registry.assertUsable("S-11101", "V1", LocalDateTime.of(2026, 10, 8, 12, 20)));
        assertTrue(stale.getMessage().contains("会话已变化"));

        // 当前版本同刻可用
        assertDoesNotThrow(() -> registry.assertUsable("S-11101", "V2", LocalDateTime.of(2026, 10, 8, 12, 20)));
    }

    @Test
    void 未登记场站与版本拒绝() {
        SnapshotRegistry registry = new SnapshotRegistry(Duration.ofMinutes(30));
        registry.register(snapshot("S-11101", "V1", "fp-A", ASSESSED_AT));
        assertThrows(IllegalArgumentException.class, () -> registry.require("S-11101", "VX"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("S-99999", "V1"));
        assertThrows(IllegalArgumentException.class, () -> registry.currentOf("S-99999"));
    }
}
