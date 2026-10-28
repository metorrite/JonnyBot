package com.runeclans.bot.commandusage;

import com.runeclans.bot.commandusage.CommandUsageCounter.Key;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandUsageCounterTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

    @Test
    void countsPerServerCommandAndDay() {
        CommandUsageCounter counter = new CommandUsageCounter();
        counter.record(1, "ban", DAY);
        counter.record(1, "ban", DAY);
        counter.record(1, "ban", DAY.plusDays(1));
        counter.record(2, "ban", DAY);
        counter.record(1, "poll", DAY);

        Map<Key, Integer> batch = counter.drain();
        assertEquals(2, batch.get(new Key(1, "ban", DAY)));
        assertEquals(1, batch.get(new Key(1, "ban", DAY.plusDays(1))));
        assertEquals(1, batch.get(new Key(2, "ban", DAY)));
        assertEquals(1, batch.get(new Key(1, "poll", DAY)));
        assertEquals(4, batch.size());
    }

    @Test
    void drainEmptiesTheCounter() {
        CommandUsageCounter counter = new CommandUsageCounter();
        counter.record(1, "ban", DAY);
        counter.drain();
        assertTrue(counter.isEmpty());
        assertTrue(counter.drain().isEmpty());
    }

    @Test
    void restorePutsAFailedBatchBackAndMergesNewUses() {
        CommandUsageCounter counter = new CommandUsageCounter();
        counter.record(1, "ban", DAY);
        Map<Key, Integer> batch = counter.drain();
        counter.record(1, "ban", DAY); // a use while the failed save was running
        counter.restore(batch);
        assertEquals(2, counter.drain().get(new Key(1, "ban", DAY)));
    }

    @Test
    void blankNamesAreIgnored() {
        CommandUsageCounter counter = new CommandUsageCounter();
        counter.record(1, "", DAY);
        counter.record(1, null, DAY);
        assertTrue(counter.isEmpty());
    }

    @Test
    void noUseIsLostWhileDrainingConcurrently() throws Exception {
        CommandUsageCounter counter = new CommandUsageCounter();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int perThread = 5_000;
        for (int t = 0; t < 4; t++) pool.submit(() -> { for (int i = 0; i < perThread; i++) counter.record(1, "ban", DAY); });
        int total = 0;
        pool.shutdown();
        while (!pool.isTerminated()) {
            total += counter.drain().values().stream().mapToInt(Integer::intValue).sum();
            pool.awaitTermination(1, TimeUnit.MILLISECONDS);
        }
        total += counter.drain().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(4 * perThread, total);
    }
}
