package com.openvpp.market.charge;

import com.openvpp.common.context.DataScope;
import com.openvpp.common.context.DataScopeResolver;
import com.openvpp.common.context.IdentityContext;
import com.openvpp.common.context.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 申报并发单测（第 53 篇"并发占用守恒"验收场景）：
 * 10 线程抢同一（场站， 事件窗口）——占用总量不超过可信容量，无重复占用；
 * 并发同请求标识——幂等去重只受理一次。
 */
class ChargeDeclarationConcurrencyTest {

    private static final LocalDateTime CREATE_AT = LocalDateTime.of(2026, 10, 8, 12, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 8, 13, 30);
    private static final LocalDateTime DECLARE_AT = LocalDateTime.of(2026, 10, 8, 13, 0);
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 10, 8, 14, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 10, 8, 16, 0);
    private static final int THREADS = 10;

    private CapacityOccupancyLedger ledger;
    private ChargeDeclarationService service;
    private DataScope stationScope;

    @BeforeEach
    void setUp() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(Duration.ofMinutes(30));
        ledger = new CapacityOccupancyLedger();
        ChargeDrEventService eventService = new ChargeDrEventService();
        service = new ChargeDeclarationService(eventService, snapshotRegistry, ledger);

        eventService.create("EV-5501", "T-1001", DrDirection.PEAK_SHAVE,
                WINDOW_START, WINDOW_END, new BigDecimal("800"), DEADLINE, CREATE_AT);
        DataScope platformScope = DataScopeResolver.resolve(
                IdentityContext.of("platform-acc", "T-1001", RoleType.PLATFORM_ADMIN, Set.of()));
        eventService.publish(platformScope, "EV-5501", CREATE_AT.plusMinutes(5));
        eventService.authorizeParticipation(platformScope, "EV-5501", List.of("S-11101"), "platform-acc",
                CREATE_AT.plusMinutes(10));
        snapshotRegistry.register(new CapabilitySnapshot("S-11101", "ev-charge-s11101",
                new BigDecimal("240"), new BigDecimal("120"),
                new BigDecimal("100"), new BigDecimal("80"),
                "V1", LocalDateTime.of(2026, 10, 8, 12, 50), "fp-A"));

        stationScope = DataScopeResolver.resolve(
                IdentityContext.of("station-acc", "T-1001", RoleType.STATION_OPERATOR, Set.of("S-11101")));
    }

    @Test
    void 十线程并发申报同场站同事件占用守恒() throws Exception {
        BigDecimal eachKw = new BigDecimal("30");
        // 可信容量 100：30×3=90 ≤ 100，第 4 笔起预占守门拒绝 —— 恰好 3 成功 7 失败
        List<Boolean> outcomes = race(THREADS, idx -> {
            try {
                service.declare(stationScope, "EV-5501", "S-11101", "T-1001",
                        eachKw, "V1", "REQ-C-" + idx, DECLARE_AT);
                return Boolean.TRUE;
            } catch (IllegalStateException e) {
                return Boolean.FALSE;
            }
        });

        long success = outcomes.stream().filter(Boolean::booleanValue).count();
        long rejected = outcomes.size() - success;
        assertEquals(3, success, "30 kW × 3 = 90 kW ≤ 100 kW，恰好 3 笔成功");
        assertEquals(7, rejected, "其余 7 笔必须被预占守门拒绝");

        // 占用总量守恒：成功笔之和 == 台账占用，且不超过可信容量
        assertEquals(0, new BigDecimal("90").compareTo(
                        ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END)),
                "并发占用总量必须与成功申报之和一致");
        assertTrue(ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END)
                        .compareTo(new BigDecimal("100")) <= 0,
                "占用总量不得超过可信容量");

        // 成功申报各自独立成单，无重复单
        DataScope platformScope = DataScopeResolver.resolve(
                IdentityContext.of("platform-acc", "T-1001", RoleType.PLATFORM_ADMIN, Set.of()));
        assertEquals(3, service.queryDeclarations(platformScope).size());
        assertEquals(3, ledger.recordsOf("S-11101").stream()
                .filter(r -> r.getAction() == CapacityOccupancyLedger.Action.OCCUPY).count());
    }

    @Test
    void 并发同请求标识仅受理一次不重复占用() throws Exception {
        BigDecimal kw = new BigDecimal("40");
        List<String> declarationIds = race(THREADS, idx -> {
            try {
                return service.declare(stationScope, "EV-5501", "S-11101", "T-1001",
                        kw, "V1", "REQ-SAME", DECLARE_AT).getDeclarationId();
            } catch (IllegalStateException e) {
                return "REJECTED-" + idx;
            }
        });

        long success = declarationIds.stream().filter(id -> !id.startsWith("REJECTED-")).count();
        assertEquals(THREADS, success, "同请求标识并发提交全部幂等命中（无业务拒绝）");
        assertEquals(1, declarationIds.stream().distinct().count(), "全部返回同一申报单标识");

        // 只受理一次、只占用一次
        assertEquals(0, kw.compareTo(ledger.occupiedKw("S-11101", WINDOW_START, WINDOW_END)));
        DataScope platformScope = DataScopeResolver.resolve(
                IdentityContext.of("platform-acc", "T-1001", RoleType.PLATFORM_ADMIN, Set.of()));
        assertEquals(1, service.queryDeclarations(platformScope).size(), "同请求标识并发只生成一张申报单");
        assertEquals(1, ledger.recordsOf("S-11101").size(), "同请求标识并发只占用一次");
    }

    /** 同一起跑线并发跑 threads 个任务，返回按提交顺序的结果 */
    private <T> List<T> race(int threads, ThrowingTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.run(idx);
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发任务未能就绪");
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private interface ThrowingTask<T> {
        T run(int idx) throws Exception;
    }
}
