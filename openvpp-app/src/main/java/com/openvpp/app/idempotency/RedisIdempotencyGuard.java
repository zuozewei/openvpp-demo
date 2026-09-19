package com.openvpp.app.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 幂等守卫（docker compose 交付默认启用，application-docker.yml 开关）。
 *
 * 在途标记 TTL 120 秒：持有方崩溃后键自动过期，不会永久阻塞后续请求；
 * 结果缓存 TTL 30 分钟：已完成结果的快速重放窗口，过期后由数据库路径接管重放。
 * 缓存读/写失败不影响业务正确性——数据库原子认领始终兜底。
 */
public class RedisIdempotencyGuard implements IdempotencyGuard {

    private static final Duration RUNNING_TTL = Duration.ofSeconds(120);
    private static final Duration RESULT_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public RedisIdempotencyGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Optional<String> cachedResult(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public boolean tryBegin(String key) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, "RUNNING", RUNNING_TTL);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void complete(String key, String resultJson) {
        redis.opsForValue().set(key, resultJson, RESULT_TTL);
    }

    @Override
    public void release(String key) {
        redis.delete(key);
    }
}
