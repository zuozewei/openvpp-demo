package com.openvpp.aggregator;

import com.openvpp.aggregator.engine.CapacityReservationLedger;
import com.openvpp.aggregator.engine.TaskWindow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量预占台账并发回归（第 3 轮复核修复）：
 * 修复前 HashMap/ArrayList 无保护，occupiedIn 流式遍历在高并发下抛
 * ConcurrentModificationException、releaseByTask 丢更新；现全方法互斥。
 */
class CapacityReservationLedgerConcurrencyTest {

    @Test
    void 并发预占查询释放_无CME无丢更新_释放计数守恒() throws Exception {
        CapacityReservationLedger ledger = new CapacityReservationLedger();
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        TaskWindow window = new TaskWindow(1000, 3600);

        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                start.await();
                int released = 0;
                for (int i = 0; i < perThread; i++) {
                    String taskId = "task-" + id + "-" + i;
                    ledger.reserve(taskId, "ess-001", window, BigDecimal.ONE);
                    // 并发遍历同资源的预占列表：修复前高频 ConcurrentModificationException
                    ledger.occupiedIn("ess-001", window);
                    ledger.reserve("hold-" + taskId, "ac-001", window, BigDecimal.ONE);
                    released += ledger.releaseByTask(taskId);
                }
                return released;
            }));
        }
        start.countDown();

        int totalReleased = 0;
        for (Future<Integer> f : futures) {
            totalReleased += f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // ess-001 的预占每条恰好释放一次（无丢更新、无重复释放）
        assertEquals(threads * perThread, totalReleased);
        // ac-001 的持有预占全部保留且占用累加正确（无丢更新）
        assertEquals(threads * perThread, ledger.occupiedIn("ac-001", window).intValue());
        assertEquals(0, ledger.occupiedIn("ess-001", window).intValue());
        assertTrue(totalReleased > 0);
    }
}
