package com.runeclans.bot.internal;

import com.runeclans.bot.internal.SeasonRules.Input;
import com.runeclans.bot.internal.SeasonRules.Row;
import com.runeclans.bot.internal.SeasonRules.Season;
import com.runeclans.bot.internal.SeasonRules.Weights;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clan seasons' pure rules (T-596): quarters in UTC, rank points and the weighted score. */
class SeasonRulesTest {
    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @Test
    void seasonsAreCalendarQuartersInUtc() {
        assertEquals("2026-q4", SeasonRules.seasonOf(at("2026-10-01T00:00:00Z")).token());
        assertEquals("2026-q3", SeasonRules.seasonOf(at("2026-09-30T23:59:59Z")).token());
        // 1 am on 1 October in UTC+2 is still September in UTC
        assertEquals("2026-q3", SeasonRules.seasonOf(at("2026-10-01T01:00:00+02:00")).token());
        assertEquals("2026-q1", SeasonRules.seasonOf(at("2026-01-01T00:00:00Z")).token());

        Season q4 = new Season(2026, 4);
        assertEquals("Oct–Dec 2026", q4.label());
        assertEquals(at("2026-10-01T00:00:00Z"), q4.from());
        assertEquals(at("2027-01-01T00:00:00Z"), q4.to());
        assertEquals("2026-10-01_2026-12-31", q4.recapPeriod());
        assertEquals(92, q4.days());
        assertEquals(new Season(2026, 3), q4.previous());
        assertEquals(new Season(2025, 4), new Season(2026, 1).previous());
    }

    @Test
    void daysLeftCountsAStartedDay() {
        Season q4 = new Season(2026, 4);
        assertEquals(66, q4.daysLeft(at("2026-10-27T00:00:00Z")));
        assertEquals(1, q4.daysLeft(at("2026-12-31T23:00:00Z")));
        assertEquals(0, q4.daysLeft(at("2027-01-01T00:00:00Z")));
    }

    @Test
    void tokensParseStrictly() {
        assertEquals(new Season(2026, 2), SeasonRules.parse(" 2026-Q2 "));
        assertNull(SeasonRules.parse("2026-q5"));
        assertNull(SeasonRules.parse("2026-10"));
        assertNull(SeasonRules.parse(null));
    }

    @Test
    void rankPointsStepDownEvenlyAndTiesShareTheBetterRank() {
        assertArrayEquals(new int[] {100, 50}, SeasonRules.rankPoints(new long[] {9_000_000, 10}));
        assertArrayEquals(new int[] {100, 100, 33, 0}, SeasonRules.rankPoints(new long[] {5, 5, 1, 0}));
        assertArrayEquals(new int[] {0, 0}, SeasonRules.rankPoints(new long[] {0, -3}));
    }

    @Test
    void equalWeightsMakeEachCategoryWorthTheSame() {
        // A tops XP by a mile, B tops points, C tops event wins: with equal weights each scores the same in their own column
        List<Row> rows = SeasonRules.standings(List.of(
                new Input("A", 500_000_000, 0, 0),
                new Input("B", 0, 300, 0),
                new Input("C", 0, 0, 2)), Weights.EQUAL);
        assertEquals(3, rows.size());
        rows.forEach(r -> assertEquals(33.3, r.score()));
        rows.forEach(r -> assertEquals(1, r.place(), "a tie shares the place"));
        assertEquals("A", rows.getFirst().rsn(), "ties go to more XP first");
    }

    @Test
    void weightsShiftTheStandingsAndZeroLeavesACategoryOut() {
        List<Input> in = List.of(new Input("Xp", 10_000_000, 10, 0), new Input("Points", 1_000_000, 500, 1));
        List<Row> equal = SeasonRules.standings(in, Weights.EQUAL);
        assertEquals("Points", equal.getFirst().rsn());

        List<Row> xpOnly = SeasonRules.standings(in, new Weights(1, 0, 0));
        assertEquals("Xp", xpOnly.getFirst().rsn());
        assertEquals(100.0, xpOnly.getFirst().score());
        assertEquals(50.0, xpOnly.get(1).score());
        assertEquals(2, xpOnly.get(1).place());
    }

    @Test
    void nobodyWithNothingIsListed() {
        List<Row> rows = SeasonRules.standings(List.of(new Input("Idle", 0, 0, 0), new Input("Busy", 1, 0, 0)), Weights.EQUAL);
        assertEquals(1, rows.size());
        assertEquals("Busy", rows.getFirst().rsn());
    }

    @Test
    void weightsAreCheckedWithAReason() {
        assertNull(SeasonRules.weightsProblem(1, 1, 1));
        assertNull(SeasonRules.weightsProblem(0, 10, 0));
        assertNotNull(SeasonRules.weightsProblem(0, 0, 0));
        assertNotNull(SeasonRules.weightsProblem(11, 1, 1));
        assertNotNull(SeasonRules.weightsProblem(-1, 1, 1));
    }

    @Test
    void badgesAndThePostNameTheTopThree() {
        assertEquals("Season champion", SeasonRules.badgeTitle(1));
        assertNull(SeasonRules.badgeTitle(4));
        List<Row> rows = SeasonRules.standings(List.of(new Input("One", 3, 0, 0), new Input("Two", 2, 0, 0), new Input("Three", 1, 0, 0),
                new Input("Four", 0, 1, 0)), new Weights(1, 0, 0));
        String text = SeasonRules.postText(new Season(2026, 3), rows);
        assertTrue(text.contains("Jul–Sep 2026"));
        assertTrue(text.contains("🥇 One — 100"));
        assertTrue(text.contains("🥉 Three — 33"));
        assertTrue(!text.contains("Four") && !text.contains("@"));
        assertTrue(SeasonRules.postText(new Season(2026, 3), List.of()).contains("Nobody scored"));
    }
}
