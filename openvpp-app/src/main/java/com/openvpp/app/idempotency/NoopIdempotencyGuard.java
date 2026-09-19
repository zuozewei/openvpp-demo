package com.openvpp.app.idempotency;

import java.util.Optional;

/**
 * 空守卫：本地教学默认（H2 + 零外部依赖）与测试环境使用。
 * 全部直通——幂等正确性完全由数据库唯一约束 + 事务内原子认领承担。
 */
public class NoopIdempotencyGuard implements IdempotencyGuard {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public Optional<String> cachedResult(String key) {
        return Optional.empty();
    }

    @Override
    public boolean tryBegin(String key) {
        return true;
    }

    @Override
    public void complete(String key, String resultJson) {
        // 无缓存介质，直通
    }

    @Override
    public void release(String key) {
        // 无缓存介质，直通
    }
}
