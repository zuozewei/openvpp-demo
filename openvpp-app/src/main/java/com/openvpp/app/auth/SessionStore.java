package com.openvpp.app.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 服务端会话仓库：内存 ConcurrentHashMap + TTL（教学口径）。
 *
 * 设计取舍：
 * 1. token 为 256 位随机数的 Base64URL 串，不透明、不可猜测，服务端不解析 token 内容，
 *    只按 token 命中会话条目；
 * 2. 过期为惰性判定——resolve 时发现过期即摘除；create 时顺带清扫一批，避免map膨胀；
 * 3. 容器化交付形态（Redis 共享会话、支持多实例与主动注销）属后续验收篇，本篇不展开。
 */
@Component
public class SessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SessionStore(@Value("${openvpp.auth.session-ttl-minutes:30}") long ttlMinutes) {
        this.ttlMillis = ttlMinutes * 60_000L;
    }

    /** 为登录成功账号开立会话，返回会话条目（token 即调用方后续请求的凭证） */
    public SessionEntry create(Account account) {
        purgeExpired();
        long now = System.currentTimeMillis();
        String token = newToken();
        SessionEntry entry = new SessionEntry(token, account.getLogin(), account.getTenantId(),
                account.getRole(), account.getStationIds(), now, now + ttlMillis);
        sessions.put(token, entry);
        return entry;
    }

    /** 按 token 取会话：不存在或已过期均返回 null（过期条目惰性摘除） */
    public SessionEntry resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        SessionEntry entry = sessions.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            sessions.remove(token, entry);
            return null;
        }
        return entry;
    }

    /** 清扫全部过期会话，返回清扫条数（create 时顺带执行，教学规模下成本可忽略） */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired(now));
        return before - sessions.size();
    }

    private String newToken() {
        byte[] buffer = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buffer);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
