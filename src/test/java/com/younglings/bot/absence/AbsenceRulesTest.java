package com.younglings.bot.absence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbsenceRulesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    void todayUpToNinetyDaysAheadIsAllowed() {
        assertNull(AbsenceRules.problem(TODAY, "", TODAY));
        assertNull(AbsenceRules.problem(TODAY.plusDays(AbsenceRules.MAX_DAYS), null, TODAY));
    }

    @Test
    void pastDaysAndTooFarAheadAreRefused() {
        assertNotNull(AbsenceRules.problem(TODAY.minusDays(1), "", TODAY));
        assertNotNull(AbsenceRules.problem(TODAY.plusDays(AbsenceRules.MAX_DAYS + 1), "", TODAY));
        assertNotNull(AbsenceRules.problem(null, "", TODAY));
    }

    @Test
    void reasonLengthIsLimited() {
        assertNull(AbsenceRules.problem(TODAY, "x".repeat(AbsenceRules.MAX_REASON), TODAY));
        assertNotNull(AbsenceRules.problem(TODAY, "x".repeat(AbsenceRules.MAX_REASON + 1), TODAY));
        // surrounding spaces don't count
        assertNull(AbsenceRules.problem(TODAY, "  " + "x".repeat(AbsenceRules.MAX_REASON) + "  ", TODAY));
    }

    @Test
    void reasonIsTrimmedToOneLine() {
        assertEquals("on holiday in Spain", AbsenceRules.cleanReason("  on holiday\n in   Spain "));
        assertEquals("", AbsenceRules.cleanReason(null));
    }

    @Test
    void aNoticeRunsToTheEndOfItsLastDay() {
        assertTrue(AbsenceRules.active(TODAY, TODAY));
        assertTrue(AbsenceRules.active(TODAY.plusDays(3), TODAY));
        assertFalse(AbsenceRules.active(TODAY.minusDays(1), TODAY));
    }

    @Test
    void daysCountTodayAsDayOne() {
        assertEquals(TODAY, AbsenceRules.untilForDays(1, TODAY));
        assertEquals(TODAY.plusDays(13), AbsenceRules.untilForDays(14, TODAY));
        assertEquals(TODAY, AbsenceRules.untilForDays(0, TODAY));
        // the longest /absence set allows is still a valid notice
        assertNull(AbsenceRules.problem(AbsenceRules.untilForDays(AbsenceRules.MAX_DAYS, TODAY), "", TODAY));
    }

    @Test
    void daysAreWrittenPlainly() {
        assertEquals("Tue 3 Nov 2026", AbsenceRules.day(LocalDate.of(2026, 11, 3)));
    }
}
