package com.runeclans.bot.tracking;

import com.runeclans.bot.runescape.RuneMetricsDates;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitadelWeekTest {
    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    @Test
    void theWeekTurnsOverTenMinutesAfterTheGameReset() {
        // Wednesday 2026-10-14
        assertEquals(utc("2026-10-14T00:10:00Z"), CitadelWeek.lastReset(utc("2026-10-14T00:10:00Z")));
        assertEquals(utc("2026-10-07T00:10:00Z"), CitadelWeek.lastReset(utc("2026-10-14T00:09:59Z")), "five minutes after the game reset it is still the old week");
        assertEquals(utc("2026-10-14T00:10:00Z"), CitadelWeek.lastReset(utc("2026-10-16T09:00:00Z")));
        assertEquals(utc("2026-10-07T00:10:00Z"), CitadelWeek.lastReset(utc("2026-10-13T23:59:59Z")));
    }

    @Test
    void theLastFinishedWeekRunsFromResetToReset() {
        var w = CitadelWeek.lastCompleted(utc("2026-10-14T12:00:00Z"));
        assertEquals(utc("2026-10-07T00:10:00Z"), w[0]);
        assertEquals(utc("2026-10-14T00:10:00Z"), w[1]);
        // at 00:05 on the Wednesday the previous week hasn't finished yet
        var early = CitadelWeek.lastCompleted(utc("2026-10-14T00:05:00Z"));
        assertEquals(utc("2026-09-30T00:10:00Z"), early[0]);
        assertEquals(utc("2026-10-07T00:10:00Z"), early[1]);
    }

    @Test
    void theCurrentWeekKeepsTheSameEndForAllSevenDays() {
        var wed = CitadelWeek.current(utc("2026-10-14T05:00:00Z"));
        var sun = CitadelWeek.current(utc("2026-10-18T23:00:00Z"));
        assertEquals(utc("2026-10-14T00:10:00Z"), wed[0]);
        assertEquals(utc("2026-10-21T00:10:00Z"), wed[1]);
        assertEquals(wed[1], sun[1]);
    }

    @Test
    void aWindowEndingOnADateStartsAWeekEarlier() {
        var w = CitadelWeek.windowEndingOn(LocalDate.of(2026, 10, 14));
        assertEquals(utc("2026-10-07T00:10:00Z"), w[0]);
        assertEquals(utc("2026-10-14T00:10:00Z"), w[1]);
        assertEquals(LocalDate.of(2026, 10, 7), CitadelWeek.weekStartOf(utc("2026-10-07T00:10:00Z")));
        assertEquals(LocalDate.of(2026, 9, 30), CitadelWeek.weekStartOf(utc("2026-10-07T00:05:00Z")));
    }

    // ---------- when something happened: the two caps from the example ----------

    @Test
    void aCapAtFiveMinutesPastMidnightCountsForTheWeekThatJustEnded() {
        // RuneMetrics writes UK time; in October that is BST, one hour ahead of UTC. A cap at 00:05 UTC on Wednesday 2026-10-14 reads "14-Oct-2026 01:05".
        Instant early = RuneMetricsDates.toInstant("14-Oct-2026 01:05");
        Instant late = RuneMetricsDates.toInstant("14-Oct-2026 01:15");
        assertEquals(Instant.parse("2026-10-14T00:05:00Z"), early);
        assertEquals(Instant.parse("2026-10-14T00:15:00Z"), late);
        var lastWeek = CitadelWeek.windowEndingOn(LocalDate.of(2026, 10, 14));
        assertTrue(!early.isBefore(lastWeek[0].toInstant()) && early.isBefore(lastWeek[1].toInstant()), "00:05 is in the week that ended at 00:10");
        assertFalse(late.isBefore(lastWeek[1].toInstant()), "00:15 is in the new week, not the old");
    }

    @Test
    void aCapMadeBeforeTheResetButNoticedHoursLaterStillCountsForItsOwnWeek() {
        // capped at 23:55 UTC on Tuesday (BST reads 00:55 Wednesday); the bot only polls at 03:00. The time it happened decides, not the poll.
        Instant happened = RuneMetricsDates.toInstant("14-Oct-2026 00:55");
        assertEquals(Instant.parse("2026-10-13T23:55:00Z"), happened);
        var thatWeek = CitadelWeek.windowEndingOn(LocalDate.of(2026, 10, 14));
        assertTrue(happened.isBefore(thatWeek[1].toInstant()));
    }

    @Test
    void ukTimeIsReadWithTheRightOffsetInSummerAndWinter() {
        assertEquals(Instant.parse("2026-09-23T22:30:00Z"), RuneMetricsDates.toInstant("23-Sep-2026 23:30"), "BST: one hour behind what is written");
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), RuneMetricsDates.toInstant("26-Oct-2026 12:00"), "GMT after the clocks go back: the same as written");
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), RuneMetricsDates.toInstant("25-Oct-2026 01:30"), "01:30 on the change day is read as the first (daylight) 01:30");
        assertNull(RuneMetricsDates.toInstant("garbage"));
        assertNull(RuneMetricsDates.toInstant(null));
        assertNotNull(RuneMetricsDates.parseOrNull("23-Sep-2026 23:30"));
    }
}
