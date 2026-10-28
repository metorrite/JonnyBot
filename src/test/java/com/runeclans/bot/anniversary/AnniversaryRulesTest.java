package com.runeclans.bot.anniversary;

import com.runeclans.bot.anniversary.AnniversaryRules.Anniversary;
import com.runeclans.bot.anniversary.AnniversaryRules.Member;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnniversaryRulesTest {
    private static final LocalDate MON = LocalDate.of(2026, 10, 19);

    @Test
    void anAnniversaryInsideTheSevenDaysIsFound() {
        Anniversary a = AnniversaryRules.inWeek("Ray", LocalDate.of(2023, 10, 22), MON, 1);
        assertEquals(3, a.years());
        assertEquals(LocalDate.of(2026, 10, 22), a.date());
    }

    @Test
    void theWeekIsThePostDayAndTheSixAfterIt() {
        assertEquals(LocalDate.of(2026, 10, 19), AnniversaryRules.inWeek("A", LocalDate.of(2024, 10, 19), MON, 1).date());
        assertEquals(LocalDate.of(2026, 10, 25), AnniversaryRules.inWeek("B", LocalDate.of(2024, 10, 25), MON, 1).date());
        assertNull(AnniversaryRules.inWeek("C", LocalDate.of(2024, 10, 26), MON, 1));
        assertNull(AnniversaryRules.inWeek("D", LocalDate.of(2024, 10, 18), MON, 1));
    }

    @Test
    void joiningThisYearIsNotAnAnniversaryAndMinimumYearsApply() {
        assertNull(AnniversaryRules.inWeek("New", LocalDate.of(2026, 10, 20), MON, 1));
        assertNull(AnniversaryRules.inWeek("Two", LocalDate.of(2024, 10, 20), MON, 3));
        assertEquals(2, AnniversaryRules.inWeek("Two", LocalDate.of(2024, 10, 20), MON, 2).years());
        assertNull(AnniversaryRules.inWeek("Unknown", null, MON, 1));
    }

    @Test
    void aWeekAcrossNewYearCountsTheRightYear() {
        LocalDate from = LocalDate.of(2026, 12, 29);
        assertEquals(LocalDate.of(2027, 1, 2), AnniversaryRules.inWeek("Jan", LocalDate.of(2020, 1, 2), from, 1).date());
        assertEquals(7, AnniversaryRules.inWeek("Jan", LocalDate.of(2020, 1, 2), from, 1).years());
        assertEquals(6, AnniversaryRules.inWeek("Dec", LocalDate.of(2020, 12, 30), from, 1).years());
    }

    @Test
    void leapDayJoinersAreCelebratedOnTheTwentyEighthInOtherYears() {
        LocalDate from = LocalDate.of(2027, 2, 25);
        assertEquals(LocalDate.of(2027, 2, 28), AnniversaryRules.inWeek("Leap", LocalDate.of(2024, 2, 29), from, 1).date());
        assertEquals(LocalDate.of(2028, 2, 29), AnniversaryRules.inWeek("Leap", LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 26), 1).date());
    }

    @Test
    void theListIsMostYearsFirstThenByDay() {
        List<Anniversary> week = AnniversaryRules.week(List.of(
                new Member("b", LocalDate.of(2025, 10, 21)), new Member("a", LocalDate.of(2020, 10, 24)), new Member("c", LocalDate.of(2025, 10, 20)),
                new Member("x", LocalDate.of(2025, 5, 1))), MON, 1);
        assertEquals(List.of("a", "c", "b"), week.stream().map(Anniversary::rsn).toList());
    }

    @Test
    void thePostIsDueOnceOnTheChosenDayFromTheChosenHour() {
        LocalDateTime monNoon = LocalDateTime.of(2026, 10, 19, 12, 5);
        assertTrue(AnniversaryRules.due(monNoon, DayOfWeek.MONDAY, 12, null));
        assertTrue(AnniversaryRules.due(monNoon, DayOfWeek.MONDAY, 9, LocalDate.of(2026, 10, 12)));
        assertFalse(AnniversaryRules.due(monNoon, DayOfWeek.MONDAY, 12, LocalDate.of(2026, 10, 19)));
        assertFalse(AnniversaryRules.due(monNoon, DayOfWeek.MONDAY, 13, null));
        assertFalse(AnniversaryRules.due(monNoon, DayOfWeek.TUESDAY, 12, null));
    }
}
