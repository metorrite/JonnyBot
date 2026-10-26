package com.younglings.bot.internal;

import com.younglings.bot.discord.DiscordLookups;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cache behind the slow dashboard pages: fresh, stale-while-refreshing, bounded first load, and writes dropping answers. */
class StaleCacheTest {
    private static final long FRESH = 1_000;
    private static final long STALE = 60_000;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final StaleCache cache = new StaleCache(now::get, pool);

    @Test
    void aFreshAnswerIsServedWithoutLoadingAgain() {
        AtomicInteger loads = new AtomicInteger();
        assertEquals("a1", cache.get("k", FRESH, STALE, 500, () -> "a" + loads.incrementAndGet()));
        now.addAndGet(FRESH - 1);
        assertEquals("a1", cache.get("k", FRESH, STALE, 500, () -> "a" + loads.incrementAndGet()));
        assertEquals(1, loads.get());
    }

    @Test
    void aStaleAnswerIsServedAtOnceAndRefreshedInTheBackground() throws Exception {
        assertEquals("old", cache.get("k", FRESH, STALE, 500, () -> "old"));
        now.addAndGet(FRESH + 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);
        assertEquals("old", cache.get("k", FRESH, STALE, 500, () -> {
            await(release);
            refreshed.countDown();
            return "new";
        }), "the stale answer must not wait for the refresh");
        release.countDown();
        assertTrue(refreshed.await(2, TimeUnit.SECONDS));
        waitUntilServes("new");
    }

    @Test
    void aSlowFirstLoadIsCutOffButStillFillsTheCache() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        assertThrows(DiscordLookups.Slow.class, () -> cache.get("k", FRESH, STALE, 50, () -> {
            await(release);
            return "late";
        }));
        release.countDown();
        waitUntilServes("late");
    }

    @Test
    void oneRefreshRunsAtATimePerKey() throws Exception {
        assertEquals("v0", cache.get("k", FRESH, STALE, 500, () -> "v0"));
        now.addAndGet(FRESH + 1);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 5; i++) {
            cache.get("k", FRESH, STALE, 500, () -> {
                loads.incrementAndGet();
                await(release);
                return "v1";
            });
        }
        release.countDown();
        waitUntilServes("v1");
        assertEquals(1, loads.get());
    }

    @Test
    void aFailedLoadKeepsTheOldAnswerAndIsNeverCached() {
        assertEquals("good", cache.get("k", FRESH, STALE, 500, () -> "good"));
        now.addAndGet(STALE + 1); // too old to serve unasked: must load, and the load fails
        assertEquals("good", cache.get("k", FRESH, STALE, 500, () -> {
            throw new IllegalStateException("boom");
        }));
        assertThrows(IllegalStateException.class, () -> cache.get("other", FRESH, STALE, 500, () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals("ok", cache.get("other", FRESH, STALE, 500, () -> "ok"));
    }

    @Test
    void invalidatingDropsAnswersAndDiscardsLoadsAlreadyUnderWay() throws Exception {
        assertEquals("a", cache.get("g1|x", FRESH, STALE, 500, () -> "a"));
        assertEquals("b", cache.get("g2|x", FRESH, STALE, 500, () -> "b"));
        cache.invalidate("g1|");
        assertEquals("a2", cache.get("g1|x", FRESH, STALE, 500, () -> "a2"));
        assertEquals("b", cache.get("g2|x", FRESH, STALE, 500, () -> "b2"), "another server's answer is untouched");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertThrows(DiscordLookups.Slow.class, () -> cache.get("g3|x", FRESH, STALE, 50, () -> {
            started.countDown();
            await(release);
            return "before the write";
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        cache.invalidate("g3|"); // a write lands while the old read is still running
        release.countDown();
        Thread.sleep(100);
        assertEquals("after the write", cache.get("g3|x", FRESH, STALE, 500, () -> "after the write"));
    }

    @Test
    void aBoundedTaskThatRunsLongSaysSoAndStillEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        assertThrows(StaleCache.Late.class, () -> cache.within(50, () -> {
            await(release);
            return "done";
        }, ended::countDown));
        release.countDown();
        assertTrue(ended.await(2, TimeUnit.SECONDS));
        assertEquals("quick", cache.within(500, () -> "quick", () -> {}));
        assertThrows(IllegalArgumentException.class, () -> cache.within(500, () -> {
            throw new IllegalArgumentException("refused");
        }, () -> {}));
    }

    @Test
    void aFullQueueAnswersSlowInsteadOfWaiting() {
        Executor refusing = task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        };
        StaleCache full = new StaleCache(now::get, refusing);
        assertThrows(DiscordLookups.Slow.class, () -> full.get("k", FRESH, STALE, 500, () -> "x"));
    }

    private void waitUntilServes(String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Object got = cache.get("k", FRESH, STALE, 500, () -> "unexpected load");
            if (expected.equals(got)) return;
            Thread.sleep(20);
        }
        assertEquals(expected, cache.get("k", FRESH, STALE, 500, () -> "unexpected load"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void answersLongPastTheirMaxStaleAreDroppedAndYoungOnesKept() {
        StaleCache direct = new StaleCache(now::get, Runnable::run);
        for (int i = 0; i < 5_000; i++) {
            final int n = i;
            direct.get("1|members?q=" + n, FRESH, STALE, 500, () -> n);
        }
        assertEquals(5_000, direct.size());
        now.addAndGet(STALE + StaleCache.GRACE_MS - 1);
        direct.get("young", FRESH, STALE, 500, () -> "y"); // a sweep runs, but everything is still inside the window
        assertEquals(5_001, direct.size());
        now.addAndGet(StaleCache.SWEEP_EVERY_MS);
        direct.get("young2", FRESH, STALE, 500, () -> "y");
        assertEquals(2, direct.size(), "only the two recent answers are left");
    }

    @Test
    void anOverStaleAnswerIsStillTheFallbackInsideTheGraceWindow() {
        StaleCache direct = new StaleCache(now::get, Runnable::run);
        direct.get("k", FRESH, STALE, 500, () -> "old");
        now.addAndGet(STALE + StaleCache.GRACE_MS - 1);
        direct.get("other", FRESH, STALE, 500, () -> "x"); // a sweep runs here
        assertEquals("old", direct.get("k", FRESH, STALE, 500, () -> {
            throw new IllegalStateException("down");
        }));
    }

}
