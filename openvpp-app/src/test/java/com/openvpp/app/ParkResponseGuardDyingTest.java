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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 停机场景回归（第 5 轮复审修复）：首次请求正常完成后 Redis 停止——
 * 守卫退化（读取未命中、登记放行），第二次同键请求走数据库原子认领兜底，
 * 重放必须返回**从数据库重建的完整结算结果**，与首次结果的金额字段逐项一致。
 * 此前重放只返回默认字段 + 幂等标记，与「直接返回既有结果」的文档承诺不符。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-dying-test-${random.uuid};AUTO_SERVER=TRUE;LOCK_TIMEOUT=30000"
})
class ParkResponseGuardDyingTest {

    @TestConfiguration
    static class DyingGuardConfig {
        @Bean
        @Primary
        IdempotencyGuard dyingGuard() {
            return new DyingGuard();
        }
    }

    /** 模拟「首次执行后 Redis 停机」的守卫：首次登记成功，之后读取恒未命中、登记恒放行、结果缓存丢失 */
    static class DyingGuard implements IdempotencyGuard {
        final AtomicInteger begins = new AtomicInteger();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Optional<String> cachedResult(String key) {
            return Optional.empty();
        }

        @Override
        public boolean tryBegin(String key) {
            return begins.incrementAndGet() == 1;
        }

        @Override
        public void complete(String key, String resultJson) {
            // 停机：结果缓存丢失
        }

        @Override
        public void release(String key) {
            // 停机：清理无效
        }

        @Override
        public void clearNamespace(String prefix) {
            // 停机：清空无效
        }
    }

    @Autowired
    private ParkResponseOrchestrator orchestrator;
    @Autowired
    private ResponseRepository repo;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        orchestrator.resetRuntimeState();
    }

    @Test
    void redis停机后重复请求_走数据库兜底_重放结果字段与首次一致() {
        DemoRunResult first = orchestrator.run("dying-001", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));

        // 第二次请求：Redis 已「停机」（缓存丢失、登记放行），走数据库重建
        DemoRunResult replay = orchestrator.run("dying-001", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));

        assertTrue(replay.isIdempotentReplay(), "停机场景下第二次请求为幂等重放");
        assertEquals(0, first.getSettleYuan().compareTo(replay.getSettleYuan()), "净实收一致");
        assertEquals(0, first.getGrossYuan().compareTo(replay.getGrossYuan()), "毛额一致");
        assertEquals(0, first.getPenaltyYuan().compareTo(replay.getPenaltyYuan()), "考核扣款一致");
        assertEquals(0, first.getResponseKwh().compareTo(replay.getResponseKwh()), "响应电量一致");
        assertEquals(first.getAllocation().size(), replay.getAllocation().size(), "分摊明细条数一致");
        for (Map.Entry<String, BigDecimal> e : first.getAllocation().entrySet()) {
            assertEquals(0, e.getValue().compareTo(replay.getAllocation().get(e.getKey())),
                    "分摊金额（键 " + e.getKey() + "）一致");
        }
        // 不重复出账
        assertEquals(5, repo.listBills("dying-001").size());
        assertEquals(1, repo.countTasks("dying-001"));
    }
}
