package com.openvpp.app.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 幂等守卫故障退化回归（第 4 轮复审修复）：
 * 此前守卫方法直接调用 Redis，连接异常传播到控制器 500，与
 * 「Redis 不可用自动退化为纯数据库路径」的文档承诺不符。
 * 修复后：读取失败=未命中、登记失败=放行、写/清失败=静默告警。
 */
class RedisIdempotencyGuardFaultTest {

    /** 启动不可用：连接被拒绝，全部操作按退化语义执行，不向业务抛异常 */
    @Test
    void redis启动不可用_全部操作退化_不向业务抛异常() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        when(redis.execute(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        RedisIdempotencyGuard guard = new RedisIdempotencyGuard(redis);

        assertTrue(guard.isEnabled());
        assertEquals(Optional.empty(), guard.cachedResult("k"), "读取失败退化为未命中");
        assertFalse(guard.tryBegin("k"), "登记失败放行（进入数据库原子认领兜底）");
        assertDoesNotThrow(() -> guard.complete("k", "{}"), "写缓存失败静默降级");
        assertDoesNotThrow(() -> guard.release("k"), "在途清理失败静默降级");
        assertDoesNotThrow(() -> guard.clearNamespace("openvpp:idempotency:run:"), "清空失败静默降级");
    }

    /** 运行中断连：先正常后故障，读取返回未命中、登记放行，写/清静默 */
    @Test
    @SuppressWarnings("unchecked")
    void redis运行中断连_读写按退化语义执行() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("k")).thenThrow(new RedisConnectionFailureException("Connection reset"));
        when(ops.setIfAbsent(eq("k"), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Connection reset"));
        doThrow(new RedisConnectionFailureException("Connection reset"))
                .when(ops).set(eq("k"), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("Connection reset"));
        RedisIdempotencyGuard guard = new RedisIdempotencyGuard(redis);

        assertEquals(Optional.empty(), guard.cachedResult("k"));
        assertFalse(guard.tryBegin("k"));
        assertDoesNotThrow(() -> guard.complete("k", "{\"responseId\":\"k\"}"));
        assertDoesNotThrow(() -> guard.release("k"));
        assertDoesNotThrow(() -> guard.clearNamespace("ns"));
    }
}
