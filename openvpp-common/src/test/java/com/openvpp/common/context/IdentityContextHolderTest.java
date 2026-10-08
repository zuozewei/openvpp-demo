package com.openvpp.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * IdentityContextHolder 单测：require 无上下文抛未授权异常、
 * 清理后不串上下文（同线程复用与跨线程隔离）。
 */
class IdentityContextHolderTest {

    @AfterEach
    void tearDown() {
        IdentityContextHolder.clear();
    }

    @Test
    void 无上下文时require抛未授权异常() {
        IdentityContextHolder.clear();

        assertThrows(UnauthorizedAccessException.class, IdentityContextHolder::require);
    }

    @Test
    void set后可require取回同一对象() {
        IdentityContext context = IdentityContext.of(
                "operator-acc-01", "tenant-openvpp-01", RoleType.OPERATOR,
                Set.of("station-001"));

        IdentityContextHolder.set(context);

        assertSame(context, IdentityContextHolder.require());
        assertSame(context, IdentityContextHolder.get());
    }

    @Test
    void clear后同线程不再残留() {
        IdentityContext first = IdentityContext.of(
                "station-acc-01", "tenant-openvpp-01", RoleType.STATION_OPERATOR,
                Set.of("station-001"));
        IdentityContextHolder.set(first);
        IdentityContextHolder.clear();

        assertNull(IdentityContextHolder.get());
        assertThrows(UnauthorizedAccessException.class, IdentityContextHolder::require);

        // 同一线程重建新上下文，不得残留旧身份
        IdentityContext second = IdentityContext.of(
                "station-acc-02", "tenant-openvpp-01", RoleType.STATION_OPERATOR,
                Set.of("station-002"));
        IdentityContextHolder.set(second);

        assertSame(second, IdentityContextHolder.require());
        assertEquals(Set.of("station-002"), IdentityContextHolder.require().getStationIds());
    }

    @Test
    void 跨线程互不串上下文() throws InterruptedException {
        IdentityContext mainContext = IdentityContext.of(
                "platform-acc-01", "tenant-openvpp-01", RoleType.PLATFORM_ADMIN, Set.of());
        IdentityContextHolder.set(mainContext);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<IdentityContext> threadARead = new AtomicReference<>();
        AtomicReference<IdentityContext> threadBRead = new AtomicReference<>();
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        IdentityContext ctxA = IdentityContext.of(
                "operator-acc-a", "tenant-openvpp-01", RoleType.OPERATOR,
                Set.of("station-a"));
        IdentityContext ctxB = IdentityContext.of(
                "operator-acc-b", "tenant-openvpp-02", RoleType.OPERATOR,
                Set.of("station-b"));

        pool.submit(() -> {
            IdentityContextHolder.set(ctxA);
            ready.countDown();
            await(release);
            threadARead.set(IdentityContextHolder.get());
            if (IdentityContextHolder.get() != ctxA) {
                failure.set(new AssertionError("线程 A 读到的上下文被篡改"));
            }
            IdentityContextHolder.clear();
            return null;
        });
        pool.submit(() -> {
            IdentityContextHolder.set(ctxB);
            ready.countDown();
            await(release);
            threadBRead.set(IdentityContextHolder.get());
            if (IdentityContextHolder.get() != ctxB) {
                failure.set(new AssertionError("线程 B 读到的上下文被篡改"));
            }
            IdentityContextHolder.clear();
            return null;
        });

        ready.await(5, TimeUnit.SECONDS);
        release.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        if (failure.get() != null) {
            throw failure.get();
        }
        // 子线程只读本线程上下文；主线程上下文不受子线程影响
        assertSame(ctxA, threadARead.get());
        assertSame(ctxB, threadBRead.get());
        assertSame(mainContext, IdentityContextHolder.require());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试等待被中断", e);
        }
    }
}
