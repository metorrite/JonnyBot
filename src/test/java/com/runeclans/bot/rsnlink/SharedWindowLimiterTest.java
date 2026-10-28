package com.runeclans.bot.rsnlink;

import com.runeclans.bot.rsnlink.LinkTestSupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared (Postgres) window limiter against a real database: the same answers as {@link WindowLimiter}, and two limiters over one
 * database (two replicas) share one count, even when they race.
 */
class SharedWindowLimiterTest {
    private static final String SCHEMA = LinkTestSupport.TEST_SCHEMA;
    private static final LinkLimits.Window THREE_A_MINUTE = new LinkLimits.Window(3, Duration.ofMinutes(1));

    private LinkDatabase database;

    @BeforeEach
    void setUp() throws Exception {
        database = new LinkDatabase(LinkTestSupport.databaseUrl(), 6);
        LinkTestSupport.resetSchema(database);
        try (Connection c = database.connection()) {
            LinkSchema.migrate(c, SCHEMA);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            LinkTestSupport.resetSchema(database);
            database.close();
        }
    }

    private int acquire(SharedWindowLimiter limiter, String key, long now) throws SQLException {
        try (Connection c = database.connection()) {
            return limiter.tryAcquire(c, key, now);
        }
    }

    private boolean wouldAllow(SharedWindowLimiter limiter, String key, long now) throws SQLException {
        try (Connection c = database.connection()) {
            return limiter.wouldAllow(c, key, now);
        }
    }

    private int rows() throws SQLException {
        try (Connection c = database.connection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM " + SCHEMA + ".rate_window")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void givesTheSameAnswersAsTheInMemoryLimiter() throws Exception {
        SharedWindowLimiter shared = new SharedWindowLimiter(SCHEMA, "t", THREE_A_MINUTE);
        WindowLimiter memory = new WindowLimiter(THREE_A_MINUTE);
        long t = 1_000_000;
        long[] times = {t, t + 1_000, t + 2_000, t + 3_000, t + 30_500, t + 59_999, t + 60_000, t + 60_001, t + 61_000, t + 62_000, t + 62_001, t + 200_000};
        for (long now : times) {
            assertEquals(memory.wouldAllow("k", now), wouldAllow(shared, "k", now), "wouldAllow at " + now);
            assertEquals(memory.tryAcquire("k", now), acquire(shared, "k", now), "tryAcquire at " + now);
        }
        assertEquals(0, acquire(shared, "other", t + 200_000), "keys are separate");
        SharedWindowLimiter otherName = new SharedWindowLimiter(SCHEMA, "u", THREE_A_MINUTE);
        assertTrue(wouldAllow(otherName, "k", t + 200_000), "limiter names are separate");
    }

    @Test
    void allowsExactlyNInAnyWindowThenSaysHowLongToWait() throws Exception {
        SharedWindowLimiter limiter = new SharedWindowLimiter(SCHEMA, "t", THREE_A_MINUTE);
        long t = 1_000_000;
        assertEquals(0, acquire(limiter, "k", t));
        assertEquals(0, acquire(limiter, "k", t + 1_000));
        assertEquals(0, acquire(limiter, "k", t + 2_000));
        assertFalse(wouldAllow(limiter, "k", t + 3_000));
        assertEquals(57, acquire(limiter, "k", t + 3_000));
        assertTrue(wouldAllow(limiter, "k", t + 60_000), "the first one has aged out");
        assertEquals(0, acquire(limiter, "k", t + 60_000));
    }

    @Test
    void twoLimitersOverOneDatabaseShareTheCount() throws Exception {
        SharedWindowLimiter replicaA = new SharedWindowLimiter(SCHEMA, "start_ip", THREE_A_MINUTE);
        SharedWindowLimiter replicaB = new SharedWindowLimiter(SCHEMA, "start_ip", THREE_A_MINUTE);
        long t = 5_000_000;
        assertEquals(0, acquire(replicaA, "ip:aa", t));
        assertEquals(0, acquire(replicaB, "ip:aa", t + 1));
        assertEquals(0, acquire(replicaA, "ip:aa", t + 2));
        assertTrue(acquire(replicaB, "ip:aa", t + 3) > 0, "the fourth is refused whichever replica gets it");
        assertFalse(wouldAllow(replicaA, "ip:aa", t + 4));
    }

    @Test
    void racingReplicasNeverAllowMoreThanTheMax() throws Exception {
        LinkLimits.Window window = new LinkLimits.Window(5, Duration.ofHours(1));
        SharedWindowLimiter replicaA = new SharedWindowLimiter(SCHEMA, "race", window);
        SharedWindowLimiter replicaB = new SharedWindowLimiter(SCHEMA, "race", window);
        int callers = 6;
        int perCaller = 5;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Integer>> work = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                SharedWindowLimiter mine = i % 2 == 0 ? replicaA : replicaB;
                long base = 9_000_000 + i;
                work.add(() -> {
                    go.await();
                    int allowed = 0;
                    for (int n = 0; n < perCaller; n++) {
                        try (Connection c = database.connection()) {
                            c.setAutoCommit(false);
                            if (mine.tryAcquire(c, "hot", base + n * 10L) == 0) allowed++;
                            c.commit();
                        }
                    }
                    return allowed;
                });
            }
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (Callable<Integer> w : work) results.add(pool.submit(w));
            go.countDown();
            int total = 0;
            for (Future<Integer> f : results) total += f.get();
            assertEquals(5, total, "exactly the max across both replicas");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRolledBackCallLeavesNoHitAndARefusalCommitKeepsIt() throws Exception {
        SharedWindowLimiter limiter = new SharedWindowLimiter(SCHEMA, "t", new LinkLimits.Window(1, Duration.ofMinutes(1)));
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            assertEquals(0, limiter.tryAcquire(c, "k", 1_000));
            c.rollback();
        }
        assertTrue(wouldAllow(limiter, "k", 1_001), "an unexpected error rolls the hit back with the route's other work");
        assertEquals(0, acquire(limiter, "k", 1_002));
        assertFalse(wouldAllow(limiter, "k", 1_003));
    }

    @Test
    void theSweepDeletesOnlyRowsWhoseNewestHitHasAgedOut() throws Exception {
        SharedWindowLimiter minute = new SharedWindowLimiter(SCHEMA, "m", THREE_A_MINUTE);
        SharedWindowLimiter hour = new SharedWindowLimiter(SCHEMA, "h", new LinkLimits.Window(3, Duration.ofHours(1)));
        long t = 1_000_000;
        acquire(minute, "old", t);
        acquire(minute, "fresh", t + 50_000);
        acquire(hour, "old", t);
        try (Connection c = database.connection()) {
            assertEquals(1, SharedWindowLimiter.sweep(c, SCHEMA, t + 60_000), "only the minute window's first row is out of its window");
        }
        assertEquals(2, rows(), "the fresh minute row and the hour row are kept");
        try (Connection c = database.connection()) {
            assertEquals(0, SharedWindowLimiter.sweep(c, SCHEMA, t + 60_001));
            assertEquals(1, SharedWindowLimiter.sweep(c, SCHEMA, t + 110_000), "the fresh row goes once its newest hit is a minute old");
        }
    }

    @Test
    void aMaxBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SharedWindowLimiter(SCHEMA, "x", new LinkLimits.Window(0, Duration.ofMinutes(1))));
    }

    @Test
    void twoServicesOverOneDatabaseShareTheStartLimit() throws Exception {
        String url = LinkTestSupport.databaseUrl();
        LinkSettings settings = LinkTestSupport.settings(url);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        LinkService a = new LinkService(settings, database, LinkDmSender.NONE, List.of(), clock, new SecureRandom());
        LinkService b = new LinkService(settings, database, LinkDmSender.NONE, List.of(), clock, new SecureRandom());
        a.start();
        b.start();
        for (int i = 0; i < 5; i++) (i % 2 == 0 ? a : b).startPairing("Zezima", null, "RS Link 1.0.0", "198.51.100." + i);
        assertEquals(429, assertThrows(LinkException.class, () -> a.startPairing("Zezima", null, "RS Link 1.0.0", "198.51.100.99")).status());
        assertEquals(429, assertThrows(LinkException.class, () -> b.startPairing("Zezima", null, "RS Link 1.0.0", "198.51.100.98")).status(),
                "5 per name per hour, counted across both");
        clock.advance(Duration.ofMinutes(61));
        // 7 per-IP rows (the refused starts still counted their IP, as before) and one per-name row.
        assertEquals(8, a.sweep()[8], "the sweep removes the aged-out windows");
        assertEquals(0, b.sweep()[8], "and the other replica finds nothing left");
    }
}
