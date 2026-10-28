package com.runeclans.bot.jobs;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-698: one live job per slot; a job whose process went quiet stops holding its slot after {@link JobRepository#STALE} and reads as failed. */
class JobRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    private static final long EVENT = 42;

    private static JobRepository at(Instant now) {
        return JobRepository.inMemory(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aRunningRowHeardFromRecentlyIsLive() {
        JobRepository.Row row = JobRepository.Row.starting("setup", T0);
        assertTrue(row.live(T0));
        assertTrue(row.live(T0.plus(JobRepository.STALE).minusSeconds(1)));
        assertFalse(row.live(T0.plus(JobRepository.STALE)), "silent for the whole STALE window: its process is gone");
        assertSame(row, row.seenAt(T0.plusSeconds(10)));
    }

    @Test
    void aSilentRunningRowReadsAsFailed() {
        JobRepository.Row row = JobRepository.Row.starting("setup", T0);
        JobRepository.Row seen = row.seenAt(T0.plus(JobRepository.STALE).plusSeconds(1));
        assertEquals("failed", seen.state());
        assertEquals(T0, seen.finishedAt());
        assertTrue(seen.message().contains("stopped part way"));
    }

    @Test
    void aFinishedRowNeverHoldsItsSlot() {
        JobRepository.Row done = new JobRepository.Row("run-1", "setup", "done", 3, 3, 0, "Done.", T0, T0, T0);
        assertFalse(done.live(T0));
        assertSame(done, done.seenAt(T0.plus(Duration.ofDays(1))));
    }

    @Test
    void aSecondClaimWhileTheFirstIsLiveIsRefused() {
        JobRepository jobs = at(T0);
        assertTrue(jobs.claim(JobRepository.EVENT, EVENT, "setup").won());
        JobRepository.Claim second = jobs.claim(JobRepository.EVENT, EVENT, "removal");
        assertFalse(second.won());
        assertEquals("setup", second.row().kind(), "the refusal names the job that holds the slot");
        assertTrue(jobs.claim(JobRepository.ROLE_FIX, EVENT, "role-fix").won(), "another scope is another slot");
    }

    @Test
    void aStaleJobLetsTheNextOneStart() {
        JobRepository jobs = at(T0);
        assertTrue(jobs.claim(JobRepository.EVENT, EVENT, "setup").won());
        jobs.clock = Clock.fixed(T0.plus(JobRepository.STALE).plusSeconds(1), ZoneOffset.UTC);
        assertTrue(jobs.claim(JobRepository.EVENT, EVENT, "setup").won());
    }

    @Test
    void theHeartbeatKeepsALongStepLive() {
        JobRepository jobs = at(T0);
        JobRepository.Claim claim = jobs.claim(JobRepository.EVENT, EVENT, "export");
        assertTrue(claim.won());
        Instant later = T0.plus(JobRepository.STALE).minusSeconds(5);
        jobs.clock = Clock.fixed(later, ZoneOffset.UTC);
        jobs.touch(JobRepository.EVENT, EVENT, claim.row().runId()); // what keepAlive runs every HEARTBEAT
        jobs.clock = Clock.fixed(later.plus(JobRepository.STALE).minusSeconds(1), ZoneOffset.UTC);
        assertFalse(jobs.claim(JobRepository.EVENT, EVENT, "setup").won());
    }

    @Test
    void aJobThatLostItsSlotCanNoLongerWriteOrKeepItAlive() {
        JobRepository jobs = at(T0);
        JobRepository.Row old = jobs.claim(JobRepository.EVENT, EVENT, "setup").row();
        Instant later = T0.plus(JobRepository.STALE).plusSeconds(1);
        jobs.clock = Clock.fixed(later, ZoneOffset.UTC);
        JobRepository.Row next = jobs.claim(JobRepository.EVENT, EVENT, "removal").row();

        JobRepository.Row lateFinish = new JobRepository.Row(old.runId(), "setup", "done", 4, 4, 0, "All set up.", T0, later, later);
        assertFalse(jobs.save(JobRepository.EVENT, EVENT, lateFinish), "the old job learns it was superseded");
        assertFalse(jobs.saveFinal(JobRepository.EVENT, EVENT, lateFinish));
        jobs.clock = Clock.fixed(later.plus(JobRepository.STALE).minusSeconds(1), ZoneOffset.UTC);
        jobs.touch(JobRepository.EVENT, EVENT, old.runId());
        JobRepository.Row stored = jobs.latest(JobRepository.EVENT, EVENT).orElseThrow();
        assertEquals(next.runId(), stored.runId());
        assertEquals("removal", stored.kind());
        assertEquals("running", stored.state(), "the old job's finish didn't free the new job's slot");
        assertEquals(later, stored.updatedAt(), "the old job's heartbeat didn't refresh the new job");
    }

    @Test
    void theHeartbeatIsWellInsideTheStaleWindow() {
        assertTrue(JobRepository.HEARTBEAT.multipliedBy(3).compareTo(JobRepository.STALE) <= 0, "a couple of missed beats must not count as a dead process");
    }
}
