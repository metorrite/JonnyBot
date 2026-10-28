package com.runeclans.bot.jobs;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-698: the job table behind the dashboard's progress, as two bot processes see it (two repositories on one database). */
class JobRepositoryDatabaseTest {
    private static final long KEY = Long.parseLong("7".repeat(18));
    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");

    private ConnectionSupplier db;
    private JobRepository a;
    private JobRepository b;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        a = new JobRepository(db);
        b = new JobRepository(db);
        a.clock = Clock.fixed(T0, ZoneOffset.UTC);
        b.clock = Clock.fixed(T0, ZoneOffset.UTC);
        clean();
    }

    @AfterEach
    void clean() throws Exception {
        if (db == null) return;
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.dashboard_job WHERE job_key = ?")) {
            s.setLong(1, KEY);
            s.executeUpdate();
        }
    }

    @Test
    void progressSavedByOneProcessIsReadByAnother() {
        assertTrue(a.claim(JobRepository.EVENT, KEY, "setup").won());
        a.save(JobRepository.EVENT, KEY, new JobRepository.Row("setup", "running", 2, 5, 0, "Making the channel #general…", T0, null, T0));
        JobRepository.Row seen = b.latest(JobRepository.EVENT, KEY).orElseThrow();
        assertEquals("setup", seen.kind());
        assertEquals(2, seen.done());
        assertEquals(5, seen.total());
        assertEquals("Making the channel #general…", seen.message());
        assertTrue(seen.live(T0));
    }

    @Test
    void anotherProcessCannotStartASecondJobForTheSameSlot() {
        assertTrue(a.claim(JobRepository.EVENT, KEY, "setup").won());
        JobRepository.Claim second = b.claim(JobRepository.EVENT, KEY, "removal");
        assertFalse(second.won());
        assertEquals("setup", second.row().kind());
    }

    @Test
    void aFinishedJobFreesTheSlot() {
        assertTrue(a.claim(JobRepository.EVENT, KEY, "setup").won());
        a.save(JobRepository.EVENT, KEY, new JobRepository.Row("setup", "done", 5, 5, 0, "Done.", T0, T0, T0));
        assertTrue(b.claim(JobRepository.EVENT, KEY, "removal").won());
        assertEquals("removal", a.latest(JobRepository.EVENT, KEY).orElseThrow().kind());
    }

    @Test
    void aJobWhoseProcessWentQuietFreesTheSlotAfterTheStaleWindow() {
        assertTrue(a.claim(JobRepository.EVENT, KEY, "setup").won());
        b.clock = Clock.fixed(T0.plus(JobRepository.STALE).minusSeconds(1), ZoneOffset.UTC);
        assertFalse(b.claim(JobRepository.EVENT, KEY, "setup").won());
        b.clock = Clock.fixed(T0.plus(JobRepository.STALE).plusSeconds(1), ZoneOffset.UTC);
        assertEquals("failed", b.latest(JobRepository.EVENT, KEY).orElseThrow().seenAt(b.now()).state());
        assertTrue(b.claim(JobRepository.EVENT, KEY, "setup").won());
    }

    @Test
    void theHeartbeatKeepsTheSlotHeld() {
        assertTrue(a.claim(JobRepository.EVENT, KEY, "export").won());
        a.clock = Clock.fixed(T0.plus(JobRepository.HEARTBEAT), ZoneOffset.UTC);
        a.touch(JobRepository.EVENT, KEY);
        b.clock = Clock.fixed(T0.plus(JobRepository.STALE).plusSeconds(1), ZoneOffset.UTC);
        assertFalse(b.claim(JobRepository.EVENT, KEY, "setup").won(), "heard from " + JobRepository.HEARTBEAT + " in: still alive");
        assertEquals(T0.plus(JobRepository.HEARTBEAT).truncatedTo(ChronoUnit.MILLIS), b.latest(JobRepository.EVENT, KEY).orElseThrow().updatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void racingClaimsFromManyProcessesHaveOneWinner() throws Exception {
        List<JobRepository> processes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            JobRepository p = new JobRepository(db);
            p.clock = Clock.fixed(T0, ZoneOffset.UTC);
            processes.add(p);
        }
        ExecutorService pool = Executors.newFixedThreadPool(processes.size());
        try {
            List<Callable<Boolean>> tries = new ArrayList<>();
            for (JobRepository p : processes) tries.add(() -> p.claim(JobRepository.EVENT, KEY, "setup").won());
            int winners = 0;
            for (Future<Boolean> f : pool.invokeAll(tries)) if (f.get()) winners++;
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }
}
