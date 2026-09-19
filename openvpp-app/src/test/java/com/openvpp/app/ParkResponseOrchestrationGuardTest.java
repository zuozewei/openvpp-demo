package com.openvpp.app;

import com.openvpp.app.idempotency.IdempotencyGuard;
import com.openvpp.app.orchestration.DemoRunResult;
import com.openvpp.app.orchestration.ParkResponseOrchestrator;
import com.openvpp.app.persistence.ResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 幂等缓存时序与生命周期回归（第 4 轮复审修复，进程内录制型守卫，语义与 Redis 实现一致）：
 *   ① 结果缓存只能在数据库事务提交后写入——事务进行中只允许存在在途标记（RUNNING），
 *     提交阶段失败绝不留下「缓存成功、数据库回滚」的虚假成功结果；
 *   ② 回滚时不写缓存、在途标记释放；
 *   ③ 演示重置清空幂等缓存命名空间，重置后同键可重新完整执行；
 *   ④ GAP（不可行）结果不缓存，在途标记立即释放，保留同键重跑语义。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-guard-test-${random.uuid};AUTO_SERVER=TRUE;LOCK_TIMEOUT=30000"
})
class ParkResponseOrchestrationGuardTest {

    @TestConfiguration
    static class RecordingGuardConfig {
        @Bean
        @Primary
        IdempotencyGuard recordingGuard() {
            return new RecordingGuard();
        }
    }

    /** 录制型守卫：tryBegin 唯一登记、complete 覆盖、release 删除、clearNamespace 按前缀清空，并录制事件序列 */
    static class RecordingGuard implements IdempotencyGuard {
        final Map<String, String> store = new ConcurrentHashMap<>();
        final List<String> events = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public synchronized Optional<String> cachedResult(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public synchronized boolean tryBegin(String key) {
            events.add("begin:" + key);
            return store.putIfAbsent(key, "RUNNING") == null;
        }

        @Override
        public synchronized void complete(String key, String resultJson) {
            events.add("commit-write:" + key);
            store.put(key, resultJson);
        }

        @Override
        public synchronized void release(String key) {
            events.add("release:" + key);
            store.remove(key);
        }

        @Override
        public synchronized void clearNamespace(String prefix) {
            events.add("clear:" + prefix);
            store.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    @Autowired
    private ParkResponseOrchestrator orchestrator;
    @SpyBean
    private ResponseRepository repo;
    @Autowired
    private IdempotencyGuard guard;

    private RecordingGuard recordingGuard() {
        return (RecordingGuard) guard;
    }

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        orchestrator.resetRuntimeState();
        recordingGuard().store.clear();
        recordingGuard().events.clear();
    }

    private String keyOf(String responseId) {
        return ParkResponseOrchestrator.RUN_GUARD_KEY_PREFIX + responseId;
    }

    @Test
    void 结果缓存只在数据库提交后写入_事务进行中仅存在在途标记() {
        AtomicReference<String> midTxCacheValue = new AtomicReference<>("NOT-CALLED");
        // 事务中途观察点：SETTLE V1 落账时刻，结果缓存必须尚未写入（只有 RUNNING 在途标记）
        doAnswer(inv -> {
            midTxCacheValue.set(recordingGuard().store.get(keyOf("guard-ok")));
            return inv.callRealMethod();
        }).when(repo).saveBill(any(), eq("PLATFORM"), any(), eq("SETTLE"), eq("V1"), any());

        DemoRunResult r = orchestrator.run("guard-ok", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));

        assertTrue(r.isFeasible());
        assertEquals("RUNNING", midTxCacheValue.get(),
                "事务进行中结果缓存不得写入（第 4 轮复审修复点），只允许在途标记");
        String key = keyOf("guard-ok");
        assertNotNull(recordingGuard().store.get(key), "事务提交后（方法返回时）结果缓存应存在");
        assertTrue(recordingGuard().store.get(key).contains("\"responseId\""), "缓存内容为序列化结果");
        assertEquals(List.of("begin:" + key, "commit-write:" + key),
                recordingGuard().events, "事件序列应为 登记 → 提交后写缓存");
    }

    @Test
    void 落库失败事务回滚_结果缓存绝不写入_在途标记释放() {
        doThrow(new RuntimeException("模拟指令落库失败"))
                .doCallRealMethod()
                .when(repo).saveInstruction(eq("guard-crash-ins-1"), eq("guard-crash"),
                        any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> orchestrator.run("guard-crash", "NORMAL", new BigDecimal("600"), new BigDecimal("900")));

        String key = keyOf("guard-crash");
        assertNull(recordingGuard().store.get(key), "回滚后不得残留结果缓存或在途标记");
        assertTrue(recordingGuard().events.contains("release:" + key), "在途标记已释放");
    }

    @Test
    void 重置演示状态_幂等缓存命名空间一并清空_同键可重新完整执行() {
        orchestrator.run("guard-reset", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));
        assertNotNull(recordingGuard().store.get(keyOf("guard-reset")));

        repo.deleteAll();
        orchestrator.resetRuntimeState();

        assertNull(recordingGuard().store.get(keyOf("guard-reset")),
                "重置必须清空幂等缓存，否则同键重跑命中旧结果、不重新落库");
        assertTrue(recordingGuard().events.contains("clear:" + ParkResponseOrchestrator.RUN_GUARD_KEY_PREFIX));

        DemoRunResult again = orchestrator.run("guard-reset", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));
        assertFalse(again.isIdempotentReplay(), "重置后同键应重新完整执行");
        assertEquals("SETTLED", repo.taskState("guard-reset"));
    }

    @Test
    void 降级路径GAP_不缓存成功结果_在途标记释放_同键可重跑() {
        DemoRunResult r = orchestrator.run("guard-gap", "DEGRADED", new BigDecimal("600"), new BigDecimal("900"));

        assertFalse(r.isFeasible());
        String key = keyOf("guard-gap");
        assertNull(recordingGuard().store.get(key), "GAP 结果不得写入缓存（保留同键重跑语义）");
        assertTrue(recordingGuard().events.contains("release:" + key), "GAP 路径释放在途标记");
    }
}
