package com.runeclans.bot.discord.jobs;

import com.runeclans.bot.util.BotScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-696: one failed database trip never stops the outbox's repeating tasks (above all the claim heartbeat). */
class DiscordOutboxKeepGoingTest {
    @Test
    void aTaskThatThrowsOnceKeepsItsSchedule() throws Exception {
        ScheduledExecutorService lane = BotScheduler.lane("test-keep-going");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch again = new CountDownLatch(3);
        ScheduledFuture<?> task = lane.scheduleWithFixedDelay(DiscordOutbox.keepGoing("test", () -> {
            again.countDown();
            if (runs.incrementAndGet() == 1) throw new IllegalStateException("database blip");
        }), 0, 10, TimeUnit.MILLISECONDS);
        try {
            assertTrue(again.await(5, TimeUnit.SECONDS), "ran again after the failure");
            assertFalse(task.isDone(), "still scheduled");
        } finally {
            task.cancel(false);
            lane.shutdown();
        }
    }
}
