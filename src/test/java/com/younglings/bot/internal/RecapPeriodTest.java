package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecapPeriodTest {
    // Tuesday 6 October 2026 (the Citadel week that began Wednesday 30 September is still running)
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T14:30:00Z");
    private static final OffsetDateTime TRACKING = OffsetDateTime.parse("2026-08-26T00:00:00Z");

    private static RecapPeriod parse(String token) {
        return RecapPeriod.parse(token, NOW, TRACKING).orElseThrow();
    }

    @Test
    void theCitadelWeekRunsWednesdayToTuesday() {
        assertEquals(LocalDate.parse("2026-09-30"), RecapPeriod.citadelWeekStart(LocalDate.parse("2026-10-06")));
        assertEquals(LocalDate.parse("2026-10-07"), RecapPeriod.citadelWeekStart(LocalDate.parse("2026-10-07")), "Wednesday starts its own week");
        assertEquals(LocalDate.parse("2026-10-07"), RecapPeriod.citadelWeekStart(LocalDate.parse("2026-10-13")));
    }

    @Test
    void thisWeekIsToDate() {
        RecapPeriod week = parse("week");
        assertEquals(OffsetDateTime.parse("2026-09-30T00:00:00Z"), week.from());
        assertEquals(NOW, week.to());
        assertTrue(week.toDate());
    }

    @Test
    void lastWeekIsTheWholePreviousCitadelWeek() {
        RecapPeriod week = parse("last-week");
        assertEquals(OffsetDateTime.parse("2026-09-23T00:00:00Z"), week.from());
        assertEquals(OffsetDateTime.parse("2026-09-30T00:00:00Z"), week.to());
        assertFalse(week.toDate());
        assertEquals(7, week.days());
    }

    @Test
    void monthsAndYears() {
        assertEquals("October 2026 so far", parse("month").label());
        RecapPeriod lastMonth = parse("last-month");
        assertEquals("September 2026", lastMonth.label());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00Z"), lastMonth.from());
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00Z"), lastMonth.to());
        assertEquals(30, lastMonth.days());

        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00Z"), parse("year").from());
        assertEquals("2025", parse("last-year").label());
    }

    @Test
    void explicitYearsAndMonthsWork() {
        assertEquals("2026 so far", parse("2026").label());
        assertEquals("2025", parse("2025").label());
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00Z"), parse("2025").to());
        assertEquals("August 2026", parse("2026-08").label());
        assertEquals("October 2026 so far", parse("2026-10").label());
    }

    @Test
    void allStartsWhereTrackingStarted() {
        RecapPeriod all = parse("all");
        assertEquals(TRACKING, all.from());
        assertTrue(all.toDate());
    }

    @Test
    void nonsenseAndTheFutureAreRefused() {
        assertTrue(RecapPeriod.parse("banana", NOW, TRACKING).isEmpty());
        assertTrue(RecapPeriod.parse("2031", NOW, TRACKING).isEmpty());
        assertTrue(RecapPeriod.parse("2026-13", NOW, TRACKING).isEmpty());
        assertTrue(RecapPeriod.parse("2027-01", NOW, TRACKING).isEmpty());
        assertTrue(RecapPeriod.parse(null, NOW, TRACKING).isEmpty());
    }

    @Test
    void rollingWindowsEndNow() {
        RecapPeriod p = parse("last-30d");
        assertEquals(NOW.minusDays(30), p.from());
        assertEquals(NOW, p.to());
        assertTrue(p.toDate());
    }

    @Test
    void mtdAndYtdAreMonthAndYear() {
        assertEquals(parse("month").from(), parse("mtd").from());
        assertEquals(parse("year").from(), parse("ytd").from());
    }

    @Test
    void customRangesAreInclusiveOfTheLastDay() {
        RecapPeriod p = parse("2026-09-01_2026-09-20");
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00Z"), p.from());
        assertEquals(OffsetDateTime.parse("2026-09-21T00:00:00Z"), p.to());
        assertFalse(p.toDate());
        assertTrue(RecapPeriod.parse("2026-09-20_2026-09-01", NOW, TRACKING).isEmpty(), "backwards range");
        assertTrue(RecapPeriod.parse("2027-01-01_2027-01-05", NOW, TRACKING).isEmpty(), "future range");
    }
}
