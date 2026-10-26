package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old keys of the website cache are dropped, while hits, single-flight and "stale beats broken" behave as before. */
class TtlCacheEvictionTest {
    private static final long TTL = 15_000;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final TtlCache cache = new TtlCache(now::get);

    @Test
    void manyDistinctKeysAreDroppedOnceTheyAreLongPastTheirTtl() {
        int keys = 20_000;
        for (int i = 0; i < keys; i++) {
            final int n = i;
            cache.get("member?rsn=p" + n, TTL, () -> n);
        }
        assertEquals(keys, cache.size(), "nothing is dropped while it is young");
        assertEquals(0, cache.lockCount(), "no lock outlives its load");

        now.addAndGet(TTL + TtlCache.GRACE_MS + 1);
        cache.get("member?rsn=fresh", TTL, () -> -1);
        assertEquals(1, cache.size(), "only the live key is left");
        assertEquals(0, cache.lockCount());
    }

    @Test
    void anExpiredValueIsStillTheFallbackInsideTheGraceWindow() {
        cache.get("a", TTL, () -> "old");
        now.addAndGet(TTL + TtlCache.GRACE_MS - 1);
        cache.get("other", TTL, () -> "x"); // a sweep runs here; "a" is still inside the window
        assertEquals("old", cache.get("a", TTL, () -> {
            throw new IllegalStateException("database down");
        }));
    }

    @Test
    void theSweepRunsAtMostOncePerInterval() {
        long half = TtlCache.SWEEP_EVERY_MS / 2;
        cache.get("x", TTL, () -> 1);
        now.addAndGet(half);
        cache.get("y", TTL, () -> 2);
        now.addAndGet(TTL + TtlCache.GRACE_MS - half + 1);
        cache.get("t1", TTL, () -> 3); // sweeps: "x" is dropped, "y" is not old enough yet
        assertEquals(2, cache.size());
        now.addAndGet(half + 1); // "y" is droppable now, but the last sweep was too recent
        cache.get("t2", TTL, () -> 4);
        assertEquals(3, cache.size(), "no second sweep inside SWEEP_EVERY_MS");
        now.addAndGet(TtlCache.SWEEP_EVERY_MS);
        cache.get("t3", TTL, () -> 5);
        assertEquals(3, cache.size(), "the next sweep drops \"y\"");
    }

    @Test
    void aFreshHitDoesNotReload() {
        AtomicInteger loads = new AtomicInteger();
        cache.get("k", TTL, loads::incrementAndGet);
        now.addAndGet(TTL - 1);
        assertEquals(1, cache.get("k", TTL, loads::incrementAndGet));
        now.addAndGet(1);
        assertEquals(2, cache.get("k", TTL, loads::incrementAndGet), "at the TTL it reloads");
    }

    @Test
    void singleFlightStillHoldsAndLeavesNoLocksBehind() throws Exception {
        TtlCache real = new TtlCache();
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        for (int round = 0; round < 20; round++) {
            String key = "k" + round;
            for (int i = 0; i < 16; i++) {
                pool.submit(() -> {
                    go.await();
                    return real.get(key, 60_000, () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return key;
                    });
                });
            }
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(20, loads.get(), "one load per key however many callers");
        assertEquals(0, real.lockCount());
    }

    @Test
    void aPracticallyForeverTtlIsNeverSwept() {
        cache.get("forever", Long.MAX_VALUE, () -> "v");
        now.addAndGet(100L * TtlCache.GRACE_MS);
        cache.get("other", TTL, () -> "x");
        assertEquals(2, cache.size());
    }
}
