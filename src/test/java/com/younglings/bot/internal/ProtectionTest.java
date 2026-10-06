package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionTest {
    @Test
    void aBurstIsAllowedUpToTheCapacityThenRefused() {
        RateLimiter limiter = new RateLimiter(3, 1_000);
        long now = 10_000;
        assertTrue(limiter.tryAcquire("u", now));
        assertTrue(limiter.tryAcquire("u", now));
        assertTrue(limiter.tryAcquire("u", now));
        assertFalse(limiter.tryAcquire("u", now), "the fourth click in the same instant is refused");
    }

    @Test
    void tokensDripBackOverTime() {
        RateLimiter limiter = new RateLimiter(2, 1_000);
        long now = 10_000;
        limiter.tryAcquire("u", now);
        limiter.tryAcquire("u", now);
        assertFalse(limiter.tryAcquire("u", now + 500), "half a second isn't enough for a new token");
        assertTrue(limiter.tryAcquire("u", now + 1_000), "a full second earns one");
        assertFalse(limiter.tryAcquire("u", now + 1_000));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, 60_000);
        assertTrue(limiter.tryAcquire("alice", 0));
        assertFalse(limiter.tryAcquire("alice", 0));
        assertTrue(limiter.tryAcquire("bob", 0), "one person's flood doesn't block another");
    }

    @Test
    void theCacheLoadsOnceForManySimultaneousRequests() throws Exception {
        TtlCache cache = new TtlCache();
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < 16; i++) {
            pool.submit(() -> {
                go.await();
                return cache.get("k", 60_000, () -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return "value";
                });
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, loads.get(), "sixteen callers, one computation");
    }

    @Test
    void aStaleValueIsServedWhenTheReloadFails() {
        TtlCache cache = new TtlCache();
        assertEquals("old", cache.get("k", 0, () -> "old"));
        assertEquals("old", cache.get("k", 0, () -> {
            throw new IllegalStateException("database down");
        }));
    }

    @Test
    void aFailureWithNothingCachedStillPropagates() {
        TtlCache cache = new TtlCache();
        assertThrows(IllegalStateException.class, () -> cache.get("k", 0, () -> {
            throw new IllegalStateException("boom");
        }));
    }

    @Test
    void invalidatingAPrefixForgetsOnlyThoseEntries() {
        TtlCache cache = new TtlCache();
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger members = new AtomicInteger();
        cache.get("polls?x", 60_000, polls::incrementAndGet);
        cache.get("members?", 60_000, members::incrementAndGet);

        cache.invalidate("polls");
        cache.get("polls?x", 60_000, polls::incrementAndGet);
        cache.get("members?", 60_000, members::incrementAndGet);

        assertEquals(2, polls.get(), "polls were reloaded");
        assertEquals(1, members.get(), "members stayed cached");
    }

    @Test
    void theDebouncerRunsABurstOnce() throws Exception {
        Debouncer debouncer = new Debouncer();
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 50; i++) debouncer.run("poll:1", 100, runs::incrementAndGet);
        Thread.sleep(400);
        assertEquals(1, runs.get(), "fifty requests in a burst, one refresh");

        debouncer.run("poll:1", 50, runs::incrementAndGet);
        Thread.sleep(300);
        assertEquals(2, runs.get(), "a later request schedules a fresh run");
    }
}
