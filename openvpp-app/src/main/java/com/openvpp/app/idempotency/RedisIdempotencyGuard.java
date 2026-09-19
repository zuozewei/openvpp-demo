package com.openvpp.app.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 幂等守卫（docker compose 交付默认启用，application-docker.yml 开关）。
 *
 * 在途标记 TTL 120 秒：持有方崩溃后键自动过期，不会永久阻塞后续请求；
 * 结果缓存 TTL 30 分钟：已完成结果的快速重放窗口，过期后由数据库路径接管重放。
 *
 * 故障退化（第 4 轮复审修复，此前 Redis 异常直接 500，与「不可用自动退化」承诺不符）：
 * 守卫是纯加速层，任何 Redis 异常（启动不可用、运行中断连、命令超时）都在方法内部
 * 按退化语义处理，绝不向业务传播——
 *   读取失败 → 未命中（走数据库路径）；登记失败 → 放行（数据库原子认领兜底）；
 *   写入/清理/清空失败 → 记录告警，在途标记由 TTL 自愈。
 * 命令与连接超时由 spring.redis.timeout / spring.redis.connect-timeout 约束，
 * 避免 Redis 挂起时请求长时间滞留（application-docker.yml 默认 2 秒）。
 * 正确性始终由数据库唯一约束 + 事务内原子认领承担。
 */
public class RedisIdempotencyGuard implements IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyGuard.class);

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
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("Redis 结果缓存读取失败，退化为未命中（数据库认领兜底）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean tryBegin(String key) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "RUNNING", RUNNING_TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("Redis 在途登记失败，跳过前置拦截（数据库认领兜底）: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void complete(String key, String resultJson) {
        try {
            redis.opsForValue().set(key, resultJson, RESULT_TTL);
        } catch (Exception e) {
            log.warn("Redis 结果缓存写入失败（数据库已提交，正确性不受影响）: {}", e.getMessage());
        }
    }

    @Override
    public void release(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis 在途标记清理失败（TTL 到期自动失效）: {}", e.getMessage());
        }
    }

    /**
     * 清空命名空间（演示重置用）：SCAN 渐进遍历前缀键后批量删除，
     * 不使用 KEYS 阻塞实例；清空失败时残留键由 TTL 到期兜底。
     */
    @Override
    public void clearNamespace(String prefix) {
        try {
            Set<String> keys = redis.execute((RedisCallback<Set<String>>) connection -> {
                Set<String> found = new HashSet<>();
                ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(500).build();
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        found.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return found;
            });
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("Redis 幂等缓存命名空间已清空: {} 共 {} 键", prefix, keys.size());
            }
        } catch (Exception e) {
            log.warn("Redis 幂等缓存清空失败（残留键由 TTL 到期失效）: {}", e.getMessage());
        }
    }
}
