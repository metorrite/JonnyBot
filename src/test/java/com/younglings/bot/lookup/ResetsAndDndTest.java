package com.younglings.bot.lookup;

import com.younglings.bot.commands.lookup.ResetsDndAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ResetsAndDndTest {
    @Test
    void dailyResetIsNextMidnightUtc() {
        assertEquals(Instant.parse("2026-10-20T00:00:00Z"), ResetTimes.nextDaily(Instant.parse("2026-10-19T13:00:00Z")));
        // exactly at the reset, the next one is a day later
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"), ResetTimes.nextDaily(Instant.parse("2026-10-20T00:00:00Z")));
    }

    @Test
    void weeklyResetIsWednesday() {
        // 2026-10-19 is a Monday
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"), ResetTimes.nextWeekly(Instant.parse("2026-10-19T13:00:00Z")));
        // on Wednesday itself after the reset, the next one is a week away
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), ResetTimes.nextWeekly(Instant.parse("2026-10-21T05:00:00Z")));
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), ResetTimes.nextWeekly(Instant.parse("2026-10-21T00:00:00Z")));
    }

    @Test
    void monthlyResetIsTheFirst() {
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), ResetTimes.nextMonthly(Instant.parse("2026-10-19T13:00:00Z")));
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), ResetTimes.nextMonthly(Instant.parse("2026-12-31T23:59:59Z")));
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), ResetTimes.nextMonthly(Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    void resetRemindersAreDueOnlyJustAfterTheirReset() {
        assertEquals("2026-10-21", ReminderKind.DAILY_RESET.dueKey(Instant.parse("2026-10-21T00:03:00Z")));
        assertNull(ReminderKind.DAILY_RESET.dueKey(Instant.parse("2026-10-21T00:30:00Z")));
        assertNull(ReminderKind.DAILY_RESET.dueKey(Instant.parse("2026-10-21T12:03:00Z")));
        assertEquals("2026-10-21", ReminderKind.WEEKLY_RESET.dueKey(Instant.parse("2026-10-21T00:03:00Z"))); // a Wednesday
        assertNull(ReminderKind.WEEKLY_RESET.dueKey(Instant.parse("2026-10-22T00:03:00Z")));
        assertEquals("2026-11-01", ReminderKind.MONTHLY_RESET.dueKey(Instant.parse("2026-11-01T00:03:00Z")));
        assertNull(ReminderKind.MONTHLY_RESET.dueKey(Instant.parse("2026-11-02T00:03:00Z")));
    }

    @Test
    void resetPostNamesTheNextOne() {
        String text = LookupReminderScheduler.resetText("Daily reset", "daily D&Ds", Instant.parse("2026-10-22T00:00:00Z"));
        assertTrue(text.contains("Daily reset"));
        assertTrue(text.contains("<t:" + Instant.parse("2026-10-22T00:00:00Z").getEpochSecond() + ":R>"));
    }

    @Test
    void dndGroupsComeFromTheWikiTable() {
        var rows = Optional.of(DndTable.parse(GePriceServiceTest.sample("dnd-frequency.json")));
        String all = ResetsDndAccess.text(ResetsDndAccess.dnd(rows, null));
        assertTrue(all.contains("Evil Tree"));
        assertTrue(all.contains("Daily"));
        String weekly = ResetsDndAccess.text(ResetsDndAccess.dnd(rows, "Weekly"));
        assertTrue(weekly.contains("Penguin Hide and Seek"));
        assertFalse(weekly.contains("Evil Tree"));
        assertTrue(all.length() < 4000 && weekly.length() < 4000);
    }

    @Test
    void dndSaysSoWhenTheWikiIsDown() {
        assertTrue(ResetsDndAccess.text(ResetsDndAccess.dnd(Optional.<List<DndTable.Entry>>empty(), null)).contains("isn't answering"));
    }

    @Test
    void resetCardHasThreeCountdowns() {
        String text = ResetsDndAccess.text(ResetsDndAccess.reset(Instant.parse("2026-10-19T13:00:00Z")));
        assertTrue(text.contains("Daily") && text.contains("Weekly") && text.contains("Monthly"));
    }
}
