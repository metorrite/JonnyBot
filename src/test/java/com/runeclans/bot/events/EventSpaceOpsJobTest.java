package com.runeclans.bot.events;

import com.runeclans.bot.jobs.JobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** T-698: an event's background job, started on one bot process, is seen and respected by another (they share the job table). */
class EventSpaceOpsJobTest {
    private static final long EVENT = 9;

    private final JobRepository jobs = JobRepository.inMemory(Clock.systemUTC());
    private final EventSpaceOps one = ops();
    private final EventSpaceOps two = ops();

    private EventSpaceOps ops() {
        return new EventSpaceOps(mock(EventRepository.class), mock(EventService.class), mock(EventSpaceRepository.class), mock(EventSpaces.class), new EventChanges(), null, jobs);
    }

    @Test
    void aJobStartedOnOneProcessShowsItsProgressOnAnotherAndBlocksASecondStart() throws Exception {
        assertNull(two.job(EVENT));
        CountDownLatch stepped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        one.start(EVENT, "setup", job -> {
            job.total(3);
            job.step("Made the role Bingo.");
            stepped.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            job.finish("All set up.");
        });
        assertTrue(stepped.await(5, TimeUnit.SECONDS));

        EventSpaceOps.Job seen = two.job(EVENT);
        assertEquals("setup", seen.kind());
        assertEquals("running", seen.state());
        assertEquals(1, seen.done());
        assertEquals(3, seen.total());
        assertEquals("Made the role Bingo.", seen.message());
        EventProblem refused = assertThrows(EventProblem.class, () -> two.start(EVENT, "removal", job -> job.finish("never")));
        assertEquals(409, refused.status());

        release.countDown();
        for (int i = 0; i < 100 && two.job(EVENT).running(); i++) Thread.sleep(20);
        assertEquals("done", two.job(EVENT).state());
        assertEquals("All set up.", two.job(EVENT).message());
        CountDownLatch ran = new CountDownLatch(1);
        two.start(EVENT, "removal", job -> {
            job.finish("Removed.");
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aJobThatThrowsIsStoredAsFailed() throws Exception {
        one.start(EVENT, "setup", job -> {
            throw new IllegalStateException("Discord said no.");
        });
        for (int i = 0; i < 100 && (two.job(EVENT) == null || two.job(EVENT).running()); i++) Thread.sleep(20);
        assertEquals("failed", two.job(EVENT).state());
        assertEquals("Discord said no.", two.job(EVENT).message());
    }
}
