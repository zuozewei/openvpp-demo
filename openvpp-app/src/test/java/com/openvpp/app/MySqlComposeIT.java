package com.openvpp.app;

import com.openvpp.app.orchestration.DemoRunResult;
import com.openvpp.app.orchestration.ParkResponseOrchestrator;
import com.openvpp.app.persistence.ResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL + Redis 集成回归（第 4 轮复审新增：此前的「MySQL 方言测试」实为
 * H2 MySQL 兼容模式，未连接真实数据库）。
 *
 * 前置（地址可用系统属性覆盖，默认 127.0.0.1:3306 / 127.0.0.1:6379）：
 *   docker compose up -d mysql redis     # 或单独起 Redis：docker run -d --name openvpp-it-redis -p 6379:6379 redis:7-alpine
 *   若宿主机 6379 已被其他项目占用，换端口启动并以 -Dopenvpp.it.redis-port=16379 运行。
 * 服务不可达时整类自动跳过（不计入 surefire 常规口径）。显式运行：
 *   mvn -s settings-openvpp.xml -pl openvpp-app test -Dtest=MySqlComposeIT -DfailIfNoTests=false
 *
 * 覆盖：真实 MySQL 建表与全链路金额、提交后 Redis 缓存写入、同键并发认领、
 * 多轮更正版本化、重置清缓存后同键重新落库（Redis 前置幂等在真实环境全程生效）。
 */
@SpringBootTest
@EnabledIf(value = "com.openvpp.app.MySqlComposeIT#composeServicesReachable",
        disabledReason = "真实 MySQL/Redis 不可达：先 docker compose up -d mysql redis（或以 -Dopenvpp.it.redis-port 指定 Redis 端口）")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/openvpp_demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=openvpp-demo",
        "openvpp.idempotency.redis-enabled=true"
})
class MySqlComposeIT {

    static String redisHost() {
        return System.getProperty("openvpp.it.redis-host", "127.0.0.1");
    }

    static int redisPort() {
        return Integer.parseInt(System.getProperty("openvpp.it.redis-port", "6379"));
    }

    static boolean composeServicesReachable() {
        boolean mysql;
        try (Connection ignored = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/openvpp_demo?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "openvpp-demo")) {
            mysql = true;
        } catch (Exception e) {
            mysql = false;
        }
        boolean redis;
        try (Socket ignored = new Socket(redisHost(), redisPort())) {
            redis = true;
        } catch (Exception e) {
            redis = false;
        }
        return mysql && redis;
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", MySqlComposeIT::redisHost);
        registry.add("spring.redis.port", () -> String.valueOf(redisPort()));
    }

    @Autowired
    private ParkResponseOrchestrator orchestrator;
    @Autowired
    private ResponseRepository repo;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        orchestrator.resetRuntimeState();
    }

    @Test
    void mysql真实库全链路_金额守恒_提交后缓存写入_多轮更正版本化() {
        DemoRunResult r = orchestrator.run("it-mysql-1", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));

        assertTrue(r.isFeasible());
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.getSettleYuan()));
        assertEquals("SETTLED", repo.taskState("it-mysql-1"));
        assertEquals(1, repo.countTasks("it-mysql-1"));
        assertEquals(0, repo.settleAmount("it-mysql-1").compareTo(repo.allocationSum("it-mysql-1", "V1")));

        // 缓存写入发生在数据库提交后：方法返回时真实 Redis 中应已有结果
        String key = ParkResponseOrchestrator.RUN_GUARD_KEY_PREFIX + "it-mysql-1";
        assertNotNull(redis.opsForValue().get(key), "事务提交后结果缓存应写入真实 Redis");

        // 重放命中（缓存或数据库路径），不重复出账
        DemoRunResult replay = orchestrator.run("it-mysql-1", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));
        assertTrue(replay.isIdempotentReplay());
        assertEquals(1, repo.countTasks("it-mysql-1"));

        // 多轮更正：版本连续独立留档（真实 MySQL 的 FOR UPDATE 串行化与 INSERT-only）
        DemoRunResult c1 = orchestrator.dispute("it-mysql-1", new BigDecimal("380"), "it-req-1");
        assertEquals("V2", c1.getCorrectionVersion());
        DemoRunResult c2 = orchestrator.dispute("it-mysql-1", new BigDecimal("430"), "it-req-2");
        assertEquals("V3", c2.getCorrectionVersion());
        DemoRunResult c1Replay = orchestrator.dispute("it-mysql-1", new BigDecimal("380"), "it-req-1");
        assertEquals("V2", c1Replay.getCorrectionVersion());
        assertTrue(c1Replay.isIdempotentReplay());
        assertEquals(3, repo.listBills("it-mysql-1").stream()
                .filter(b -> "SETTLE".equals(b.get("BILL_TYPE"))).count());
        assertEquals(2, repo.countCorrections("it-mysql-1"));
    }

    @Test
    void mysql真实库同键12路并发_仅一次完整执行_唯一任务行() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DemoRunResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return orchestrator.run("it-conc-12", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));
                }));
            }
            ready.await();
            start.countDown();
            int executed = 0;
            for (Future<DemoRunResult> f : futures) {
                if (!f.get(120, TimeUnit.SECONDS).isIdempotentReplay()) {
                    executed++;
                }
            }
            assertEquals(1, executed, "真实 MySQL 下同键并发必须只有一路完整执行");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, repo.countTasks("it-conc-12"));
        assertEquals("SETTLED", repo.taskState("it-conc-12"));
    }

    @Test
    void mysql真实库同键12路并发纠偏_12个连续版本不覆盖() throws Exception {
        orchestrator.run("it-corr-12", "NORMAL", new BigDecimal("650"), new BigDecimal("900"));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DemoRunResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return orchestrator.dispute("it-corr-12", new BigDecimal("380"), "it-corr-req-" + id);
                }));
            }
            ready.await();
            start.countDown();
            Set<String> versions = new HashSet<>();
            for (Future<DemoRunResult> f : futures) {
                versions.add(f.get(120, TimeUnit.SECONDS).getCorrectionVersion());
            }
            assertEquals(threads, versions.size(), "真实 MySQL 下并发纠偏版本不得重复: " + versions);
            for (int v = 2; v <= threads + 1; v++) {
                assertTrue(versions.contains("V" + v), "缺少连续版本 V" + v + ": " + versions);
            }
        } finally {
            pool.shutdownNow();
        }
        // 回归锚点：MySQL 默认 REPEATABLE READ 曾使锁下快照读拿到过期版本号，
        // 并发更正撞账单主键 500 只出 3 版——现为显式 READ_COMMITTED（第 4 轮容器实测修复）
        assertEquals(threads + 1, repo.listBills("it-corr-12").stream()
                .filter(b -> "SETTLE".equals(b.get("BILL_TYPE"))).count());
        assertEquals(threads, repo.countCorrections("it-corr-12"));
    }

    @Test
    void mysqlRedis真实环境_重置清空缓存命名空间_同键重新落库() {
        orchestrator.run("it-reset", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));
        String key = ParkResponseOrchestrator.RUN_GUARD_KEY_PREFIX + "it-reset";
        assertNotNull(redis.opsForValue().get(key), "首次执行后结果缓存应存在");

        repo.deleteAll();
        orchestrator.resetRuntimeState();
        assertNull(redis.opsForValue().get(key), "重置必须清空 Redis 幂等缓存命名空间");

        DemoRunResult again = orchestrator.run("it-reset", "NORMAL", new BigDecimal("600"), new BigDecimal("900"));
        assertFalse(again.isIdempotentReplay(), "重置后同键不得命中旧缓存，应重新完整落库");
        assertEquals("SETTLED", repo.taskState("it-reset"));
        assertEquals(1, repo.countTasks("it-reset"));
    }
}
