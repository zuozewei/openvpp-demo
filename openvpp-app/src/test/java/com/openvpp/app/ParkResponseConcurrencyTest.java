package com.openvpp.app;

import com.openvpp.app.orchestration.DemoRunResult;
import com.openvpp.app.orchestration.ParkResponseOrchestrator;
import com.openvpp.app.persistence.ResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 贯穿案例并发正确性回归（第 3 轮复核修复）。
 *
 * 修复前实测缺陷：同一 responseId 并发 12 路，11 路进入完整首次执行、1 路 500；
 * 并发争议更正 12 路全部返回成功但相互覆盖，数据库只留 3 个版本。
 *
 * 修复机制：run 入口事务内原子认领（response_id 唯一主键即认领锁，后到者阻塞至
 * 持有方提交后转幂等重放，持有方回滚则后到者接管）；争议更正以 correctionRequestId
 * 留档唯一键拦截同键重复提交，FOR UPDATE 锁任务行串行化版本分配，审计账单 INSERT-only。
 *
 * 数值断言沿用 PARK-DEMO.md 手工核算底稿：申报 600 → 净实收 1200.00。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 每个测试类独立临时 H2 文件库；LOCK_TIMEOUT 给同键认领阻塞留足等待窗口；
        // 并发路数高于默认连接池（10），测试扩池避免排队等待掩盖认领行为
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/openvpp-conc-test-${random.uuid};AUTO_SERVER=TRUE;LOCK_TIMEOUT=30000",
        "spring.datasource.hikari.maximum-pool-size=25"
})
class ParkResponseConcurrencyTest {

    @Autowired
    private ParkResponseOrchestrator orchestrator;
    @Autowired
    private ResponseRepository repo;

    private static final BigDecimal DECLARED = new BigDecimal("600");
    private static final BigDecimal TARGET = new BigDecimal("900");

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        orchestrator.resetRuntimeState();
    }

    /** 提交并发任务并等待全部完成（任一路抛异常即测试失败） */
    private <T> List<T> runConcurrently(int threads, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.execute(id);
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Task<T> {
        T execute(int id) throws Exception;
    }

    @Test
    void 同键50路并发_仅一次完整执行_任务指令账单唯一() throws Exception {
        int threads = 50;
        List<DemoRunResult> results = runConcurrently(threads,
                id -> orchestrator.run("conc-50", "NORMAL", DECLARED, TARGET));

        int executed = 0;
        int replayed = 0;
        for (DemoRunResult r : results) {
            if (r.isIdempotentReplay()) {
                replayed++;
            } else {
                executed++;
                // 唯一的执行路：金额与底稿一致
                assertEquals(0, new BigDecimal("1200.00").compareTo(r.getSettleYuan()));
            }
        }
        assertEquals(1, executed, "同键并发必须只有一路完整执行");
        assertEquals(threads - 1, replayed, "其余各路必须幂等重放");

        // 库表唯一性：1 个任务行、3 条指令（3 类资源）、1 版 V1 账单
        assertEquals(1, repo.countTasks("conc-50"), "同键并发不得产生重复任务行");
        assertEquals("SETTLED", repo.taskState("conc-50"));
        assertEquals(3, repo.listInstructions("conc-50").size());
        List<java.util.Map<String, Object>> bills = repo.listBills("conc-50");
        assertEquals(5, bills.size(), "SETTLE×1 + PLATFORM_CUT×1 + SHARE×3");
        // 资金守恒：分配侧 = 净实收（不重复出账）
        assertEquals(0, new BigDecimal("1200.00").compareTo(repo.settleAmount("conc-50")));
        assertEquals(0, repo.settleAmount("conc-50").compareTo(repo.allocationSum("conc-50", "V1")));
    }

    @Test
    void 同键12路并发纠偏_12个连续版本_不覆盖不丢失() throws Exception {
        orchestrator.run("conc-corr", "NORMAL", new BigDecimal("650"), TARGET);

        int threads = 12;
        List<DemoRunResult> results = runConcurrently(threads, id ->
                orchestrator.dispute("conc-corr", new BigDecimal("380"), "corr-req-" + id));

        // 12 个不同纠偏请求 → 12 个连续且互不覆盖的版本（V2..V13）
        Set<String> versions = new HashSet<>();
        for (DemoRunResult r : results) {
            assertNotNull(r.getCorrectionVersion(), "每个纠偏请求都必须返回版本号");
            versions.add(r.getCorrectionVersion());
        }
        assertEquals(threads, versions.size(), "并发纠偏版本不得重复: " + versions);
        for (int v = 2; v <= threads + 1; v++) {
            assertTrue(versions.contains("V" + v), "缺少连续版本 V" + v + ": " + versions);
        }
        // 原始 V1 + 12 个更正版本全部留档
        assertEquals(threads + 1, repo.listBills("conc-corr").stream()
                .filter(b -> "SETTLE".equals(b.get("BILL_TYPE"))).count());
        assertEquals(threads, repo.countCorrections("conc-corr"));
        // 每个版本独立守恒：更正账单不允许半套或覆盖
        for (int v = 2; v <= threads + 1; v++) {
            String version = "V" + v;
            assertEquals(0, repo.settleAmountOfVersion("conc-corr", version)
                            .compareTo(repo.allocationSum("conc-corr", version)),
                    "版本 " + version + " 分配侧必须等于同版本净实收");
        }
    }

    @Test
    void 同键同一纠偏请求并发8路_仅一次出账_全部返回原版本() throws Exception {
        orchestrator.run("corr-same", "NORMAL", new BigDecimal("650"), TARGET);

        int threads = 8;
        List<DemoRunResult> results = runConcurrently(threads, id ->
                orchestrator.dispute("corr-same", new BigDecimal("380"), "same-req"));

        for (DemoRunResult r : results) {
            assertEquals("V2", r.getCorrectionVersion(), "同一纠偏请求必须返回同一版本");
        }
        long replays = results.stream().filter(DemoRunResult::isIdempotentReplay).count();
        assertTrue(replays >= threads - 1, "至少 7 路为幂等重放");
        // 只出一次账：V1 + V2 两版净实收，一条 CORRECTION 冲正，一条留档
        assertEquals(2, repo.listBills("corr-same").stream()
                .filter(b -> "SETTLE".equals(b.get("BILL_TYPE"))).count());
        assertEquals(1, repo.listBills("corr-same").stream()
                .filter(b -> "CORRECTION".equals(b.get("BILL_TYPE"))).count());
        assertEquals(1, repo.countCorrections("corr-same"));
    }

    @Test
    void 同一纠偏请求顺序重复提交_返回原结果_不新增版本() throws Exception {
        orchestrator.run("corr-idem", "NORMAL", new BigDecimal("650"), TARGET);

        DemoRunResult first = orchestrator.dispute("corr-idem", new BigDecimal("380"), "req-001");
        assertEquals("V2", first.getCorrectionVersion());
        assertEquals(0, new BigDecimal("10.00").compareTo(first.getCorrectionDiffYuan()));
        assertFalse(first.isIdempotentReplay());

        DemoRunResult replay = orchestrator.dispute("corr-idem", new BigDecimal("380"), "req-001");
        assertEquals("V2", replay.getCorrectionVersion(), "重复提交返回原版本");
        assertTrue(replay.isIdempotentReplay(), "重复提交标记幂等重放");
        assertEquals(0, first.getCorrectionDiffYuan().compareTo(replay.getCorrectionDiffYuan()));
        assertEquals(0, first.getCorrectedSettleYuan().compareTo(replay.getCorrectedSettleYuan()));
        // 金额比较用 compareTo（BigDecimal.equals 对 scale 敏感：库中 DECIMAL(14,2) 与计算值标度不同）
        assertEquals(first.getAllocation().size(), replay.getAllocation().size(), "分摊键数一致");
        first.getAllocation().forEach((k, v) ->
                assertEquals(0, v.compareTo(replay.getAllocation().get(k)),
                        "分摊金额（键 " + k + "）重放与原结果一致"));
        assertEquals(2, repo.listBills("corr-idem").stream()
                .filter(b -> "SETTLE".equals(b.get("BILL_TYPE"))).count(), "不新增版本");
    }
}
