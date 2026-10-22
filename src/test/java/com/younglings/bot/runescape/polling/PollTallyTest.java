package com.younglings.bot.runescape.polling;

import com.younglings.bot.runescape.polling.PollTally.Counts;
import com.younglings.bot.runescape.polling.PollTally.Kind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The day's RuneMetrics counters per tier: only counts, rolled over at the UTC day. */
class PollTallyTest {
    @Test
    void countsAreKeptPerTierAndKind() {
        PollTally tally = new PollTally(() -> LocalDate.of(2026, 10, 20));

        tally.record(PollPriority.CLAN, Kind.SENT);
        tally.record(PollPriority.CLAN, Kind.SENT);
        tally.record(PollPriority.CLAN, Kind.RATE_LIMITED);
        tally.record(PollPriority.LINKED, Kind.REUSED);
        tally.record(PollPriority.INTERACTIVE, Kind.FAILED);
        tally.record(PollPriority.INTERACTIVE, Kind.GAVE_UP);

        var day = tally.snapshot();
        assertEquals(new Counts(2, 0, 1, 0, 0), day.byPriority().get(PollPriority.CLAN));
        assertEquals(new Counts(0, 1, 0, 0, 0), day.byPriority().get(PollPriority.LINKED));
        assertEquals(new Counts(0, 0, 0, 1, 1), day.byPriority().get(PollPriority.INTERACTIVE));
        assertNull(day.byPriority().get(PollPriority.PREMIUM_CLAN));
    }

    @Test
    void theDayRollsOverOnceAndTheFinishedDayIsHandedBack() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 20));
        PollTally tally = new PollTally(today::get);
        tally.record(PollPriority.CLAN, Kind.SENT);

        assertNull(tally.rollIfNewDay(), "same day: nothing to hand back");

        today.set(LocalDate.of(2026, 10, 21));
        var finished = tally.rollIfNewDay();

        assertEquals(LocalDate.of(2026, 10, 20), finished.day());
        assertEquals(1, finished.byPriority().get(PollPriority.CLAN).sent());
        assertTrue(tally.snapshot().byPriority().isEmpty(), "the new day starts empty");
        assertEquals(LocalDate.of(2026, 10, 21), tally.snapshot().day());
        assertNull(tally.rollIfNewDay());
    }

    @Test
    void aSavedRowAndTheLiveCountersNeverMakeTheDaySmaller() {
        var saved = new RuneMetricsBudget.Row(LocalDate.of(2026, 10, 20), PollPriority.CLAN, new Counts(500, 10, 3, 1, 0));
        var live = new RuneMetricsBudget.Row(LocalDate.of(2026, 10, 20), PollPriority.CLAN, new Counts(40, 20, 0, 0, 0));

        assertEquals(new Counts(500, 20, 3, 1, 0), RuneMetricsBudget.larger(saved, live).counts());
    }

    @Test
    void demandIsPlayersTimesRequestsPerDay() {
        assertEquals(240.0, RuneMetricsBudget.requestsPerDay(30, java.time.Duration.ofMinutes(180)), 0.001);
        assertEquals(0.0, RuneMetricsBudget.requestsPerDay(30, java.time.Duration.ZERO));
    }
}
