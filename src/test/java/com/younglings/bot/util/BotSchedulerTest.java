package com.younglings.bot.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A lane keeps what each service's own single-thread scheduler promised, on shared threads (T-184). */
class BotSchedulerTest {

    @Test
    void aLanesTasksNeverRunAtTheSameTime() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-serial");
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            lane.execute(() -> {
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                sleep(5);
                running.decrementAndGet();
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, most.get(), "two tasks of one lane overlapped");
    }

    @Test
    void differentLanesDoNotWaitForEachOther() throws Exception {
        ScheduledExecutorService slow = BotScheduler.lane("t184-slow");
        ScheduledExecutorService quick = BotScheduler.lane("t184-quick");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch quickRan = new CountDownLatch(1);
        slow.execute(() -> await(release));
        quick.execute(quickRan::countDown);
        assertTrue(quickRan.await(2, TimeUnit.SECONDS), "a long job on one lane held up another lane");
        release.countDown();
    }

    @Test
    void aFixedDelayTaskRepeatsUntilCancelled() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-delay");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        ScheduledFuture<?> future = lane.scheduleWithFixedDelay(() -> {
            runs.incrementAndGet();
            three.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS);
        assertTrue(three.await(2, TimeUnit.SECONDS));
        assertTrue(future.cancel(false));
        int after = runs.get();
        Thread.sleep(80);
        assertTrue(runs.get() <= after + 1, "it kept running after cancel: " + runs.get() + " vs " + after);
        assertTrue(future.isCancelled());
    }

    @Test
    void aFixedRateTaskNeverOverlapsItself() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-rate");
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch five = new CountDownLatch(5);
        ScheduledFuture<?> future = lane.scheduleAtFixedRate(() -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(15); // longer than the period
            running.decrementAndGet();
            five.countDown();
        }, 0, 5, TimeUnit.MILLISECONDS);
        assertTrue(five.await(3, TimeUnit.SECONDS));
        future.cancel(false);
        assertEquals(1, most.get());
    }

    @Test
    void aRepeatingTaskThatThrowsStopsRepeatingAsBefore() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-throws");
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> future = lane.scheduleWithFixedDelay(() -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        }, 0, 5, TimeUnit.MILLISECONDS);
        Thread.sleep(150);
        assertEquals(1, runs.get());
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
    }

    @Test
    void aOneShotRunsOnceAfterItsDelayAndCanBeCancelledBefore() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-once");
        AtomicInteger ran = new AtomicInteger();
        ScheduledFuture<?> cancelled = lane.schedule(ran::incrementAndGet, 200, TimeUnit.MILLISECONDS);
        assertTrue(cancelled.cancel(false));
        ScheduledFuture<String> answered = lane.schedule(() -> "done", 20, TimeUnit.MILLISECONDS);
        assertEquals("done", answered.get(2, TimeUnit.SECONDS));
        Thread.sleep(250);
        assertEquals(0, ran.get(), "a cancelled one-shot ran");
    }

    @Test
    void aTaskRunsUnderItsLanesName() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-named");
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        lane.execute(() -> {
            seen.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals("t184-named", seen.get());
    }

    @Test
    void aShutDownLaneStopsItsRepeatingTasksRefusesNewWorkAndTerminates() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("t184-shutdown");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch once = new CountDownLatch(1);
        ScheduledFuture<?> future = lane.scheduleAtFixedRate(() -> {
            runs.incrementAndGet();
            once.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS);
        assertTrue(once.await(2, TimeUnit.SECONDS));
        lane.shutdown();
        assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
        int after = runs.get();
        Thread.sleep(60);
        assertEquals(after, runs.get(), "it kept running after shutdown");
        assertThrows(RejectedExecutionException.class, () -> lane.execute(() -> {}));
    }

    @Test
    void idleLanesHoldNoThreads() throws Exception {
        for (int i = 0; i < 40; i++) BotScheduler.lane("t184-idle-" + i).scheduleWithFixedDelay(() -> {}, 1, 1, TimeUnit.HOURS);
        assertTrue(BotScheduler.threads() < 40, "forty idle lanes should not mean forty threads: " + BotScheduler.threads());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
