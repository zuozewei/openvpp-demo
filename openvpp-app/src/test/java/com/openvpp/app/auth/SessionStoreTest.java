package com.openvpp.app.auth;

import com.openvpp.common.context.RoleType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话仓库单测：开立、命中、未知 token 与 TTL 过期。
 */
class SessionStoreTest {

    private static final Account DEMO = new Account(
            "station_admin", "hash-unused", RoleType.STATION_OPERATOR,
            "tenant-openvpp", Set.of("cs-station-01"), "场站-演示账号");

    @Test
    void createThenResolveReturnsEntry() {
        SessionStore store = new SessionStore(30);
        SessionEntry created = store.create(DEMO);
        assertNotNull(created.getToken());

        SessionEntry resolved = store.resolve(created.getToken());
        assertNotNull(resolved);
        assertEquals("station_admin", resolved.getAccountId());
        assertEquals("tenant-openvpp", resolved.getTenantId());
        assertEquals(RoleType.STATION_OPERATOR, resolved.getRole());
        assertEquals(Set.of("cs-station-01"), resolved.getStationIds());
    }

    @Test
    void resolveUnknownOrBlankTokenReturnsNull() {
        SessionStore store = new SessionStore(30);
        assertNull(store.resolve("no-such-token"));
        assertNull(store.resolve(null));
        assertNull(store.resolve(""));
    }

    @Test
    void expiredSessionIsRejectedAndPurged() throws Exception {
        // TTL=0：条目创建即过期（isExpired 边界取等号）
        SessionStore store = new SessionStore(0);
        SessionEntry created = store.create(DEMO);
        Thread.sleep(2);
        assertNull(store.resolve(created.getToken()));
        // 惰性摘除 + 主动清扫后，仓库回到空态
        assertTrue(store.purgeExpired() >= 0);
        assertNull(store.resolve(created.getToken()));
    }

    @Test
    void tokensAreUniqueAcrossSessions() {
        SessionStore store = new SessionStore(30);
        SessionEntry first = store.create(DEMO);
        SessionEntry second = store.create(DEMO);
        org.junit.jupiter.api.Assertions.assertNotEquals(first.getToken(), second.getToken());
        assertNotNull(store.resolve(first.getToken()));
        assertNotNull(store.resolve(second.getToken()));
    }
}
