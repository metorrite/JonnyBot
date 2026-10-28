package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ---------- the key a member's "opened it" mark is stored under (RecapGate) ----------

    private static String key(String token) {
        return parse(token).key().orElse(null);
    }

    private static String keyAt(String token, OffsetDateTime now) {
        return RecapPeriod.parse(token, now, TRACKING).orElseThrow().key().orElse(null);
    }

    @Test
    void aCitadelWeekIsKeyedByItsWednesdayWhateverTokenAskedForIt() {
        assertEquals("week:2026-09-30:running", key("week"));
        assertEquals("week:2026-09-30:running", key("2026-09-30_2026-10-06"), "the same week by its dates, still running");
        assertEquals("week:2026-09-23:final", key("last-week"));
        assertEquals("week:2026-09-23:final", key("2026-09-23_2026-09-29"));
        assertEquals("week:2026-09-30:final", keyAt("last-week", OffsetDateTime.parse("2026-10-08T09:00:00Z")), "the same week once it ended");
    }

    @Test
    void aMonthIsKeyedByItsMonthWhateverTokenAskedForIt() {
        for (String token : List.of("month", "mtd", "2026-10", "2026-10-01_2026-10-31")) assertEquals("month:2026-10:running", key(token), token);
        for (String token : List.of("last-month", "2026-09", "2026-09-01_2026-09-30")) assertEquals("month:2026-09:final", key(token), token);
    }

    @Test
    void aYearIsKeyedByItsYearWhateverTokenAskedForIt() {
        for (String token : List.of("year", "ytd", "2026", "2026-01-01_2026-12-31")) assertEquals("year:2026:running", key(token), token);
        for (String token : List.of("last-year", "2025", "2025-01-01_2025-12-31")) assertEquals("year:2025:final", key(token), token);
    }

    @Test
    void openingAWindowWhileItRunsIsNotOpeningTheFinishedOne() {
        OffsetDateTime november = OffsetDateTime.parse("2026-11-02T10:00:00Z");
        assertEquals("month:2026-10:final", keyAt("last-month", november));
        assertEquals("month:2026-10:final", keyAt("2026-10", november));
        assertNotEquals(key("month"), keyAt("last-month", november), "October so far and the whole October are two recaps");
        assertEquals("year:2026:final", keyAt("last-year", OffsetDateTime.parse("2027-01-03T10:00:00Z")));
    }

    @Test
    void rollingWindowsAndAllHaveADatelessKey() {
        assertEquals("rolling:30d", key("last-30d"));
        assertEquals("rolling:7d", key("last-7d"));
        assertEquals("rolling:30d", keyAt("last-30d", NOW.plusDays(40)), "the same key whenever it is asked");
        assertEquals("all", key("all"));
    }

    @Test
    void otherCustomRangesHaveNoKey() {
        for (String token : List.of("2026-09-01_2026-09-20", "2026-09-15_2026-09-15", "2026-09-24_2026-09-30", "2026-09-01_2026-10-31", "2026-02-01_2026-12-31")) {
            assertEquals(Optional.empty(), parse(token).key(), token + " is not one whole week, month or year");
        }
    }

    @Test
    void aRecapAnswersOwnPeriodGivesItsKeyEvenAfterTheWindowRolledOver() {
        // an answer for "month" built a minute before midnight, read again (from a cache) after the month turned
        RecapPeriod built = RecapPeriod.parse("month", OffsetDateTime.parse("2026-10-31T23:59:00Z"), TRACKING).orElseThrow();
        assertEquals(Optional.of("month:2026-10:running"), RecapPeriod.key(built.token(), built.from(), built.to(), built.toDate()));
        for (String token : List.of("week", "last-week", "2026-09-01_2026-09-30", "2026-10-01_2026-10-31", "last-30d", "all", "2025", "year")) {
            RecapPeriod p = parse(token);
            assertEquals(p.key(), RecapPeriod.key(p.token(), OffsetDateTime.parse(p.from().toString()), OffsetDateTime.parse(p.to().toString()), p.toDate()), token);
        }
    }

    @Test
    void aPeriodThatDoesNotMatchItsTokenHasNoKey() {
        RecapPeriod month = parse("month");
        assertEquals(Optional.empty(), RecapPeriod.key("month", month.from().plusDays(1), month.to(), true), "a window this token never gives");
        assertEquals(Optional.empty(), RecapPeriod.key("month", month.from(), month.to(), false), "running described as finished");
        assertEquals(Optional.empty(), RecapPeriod.key("banana", month.from(), month.to(), true));
        assertEquals(Optional.empty(), RecapPeriod.key(null, month.from(), month.to(), true));
    }
}
