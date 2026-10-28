package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyDigestSchedulerTest {
    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @Test
    void theReportRunsOnWednesdayAtNoonUtc() {
        // Tuesday 2026-10-13 18:00 -> Wednesday 12:00 is 18 hours away
        assertEquals(Duration.ofHours(18), WeeklyDigestScheduler.durationUntilNextWednesdayNoonUtc(at("2026-10-13T18:00:00Z")));
        // Wednesday before noon: later the same day
        assertEquals(Duration.ofHours(1), WeeklyDigestScheduler.durationUntilNextWednesdayNoonUtc(at("2026-10-14T11:00:00Z")));
        // Wednesday after noon: next week
        assertEquals(Duration.ofDays(7).minusHours(1), WeeklyDigestScheduler.durationUntilNextWednesdayNoonUtc(at("2026-10-14T13:00:00Z")));
    }

    @Test
    void theClockCheckOnlyComplainsAboutMoreThanAClockQuirk() {
        assertFalse(RuneMetricsClockCheck.looksWrong(0, 500));
        assertFalse(RuneMetricsClockCheck.looksWrong(1, 500), "one odd row is noise");
        assertFalse(RuneMetricsClockCheck.looksWrong(30, 10), "too few rows to say");
        assertTrue(RuneMetricsClockCheck.looksWrong(95, 500), "a fifth of the rows in the future means the zone is wrong");
    }
}
