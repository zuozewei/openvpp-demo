package com.openvpp.app.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等守卫装配：
 *   openvpp.idempotency.redis-enabled=true（docker 交付）→ Redis 守卫；
 *   否则默认 Noop 守卫（本地教学/测试，幂等正确性由数据库原子认领承担）。
 */
@Configuration
public class IdempotencyGuardConfig {

    @Bean
    @ConditionalOnProperty(prefix = "openvpp.idempotency", name = "redis-enabled", havingValue = "true")
    public IdempotencyGuard redisIdempotencyGuard(StringRedisTemplate redis) {
        return new RedisIdempotencyGuard(redis);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyGuard.class)
    public IdempotencyGuard noopIdempotencyGuard() {
        return new NoopIdempotencyGuard();
    }
}
