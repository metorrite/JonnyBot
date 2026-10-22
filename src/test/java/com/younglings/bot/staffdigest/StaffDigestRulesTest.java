package com.younglings.bot.staffdigest;

import com.younglings.bot.staffdigest.StaffDigestRules.EventLine;
import com.younglings.bot.staffdigest.StaffDigestRules.Facts;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffDigestRulesTest {
    private static final OffsetDateTime RAID = OffsetDateTime.of(2026, 10, 24, 19, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void aClanServerWithTicketsGetsEveryLine() {
        List<String> lines = StaffDigestRules.lines(new Facts(69, 4, 2, 1, 0, 3, 1, List.of(new EventLine("Raid night", RAID))));
        assertEquals("### 📋 Staff digest", lines.get(0));
        assertEquals("**Clan** · 69 members · 4 inactive 30+ days · 2 unverified · 1 promotion due", lines.get(1));
        assertEquals("**Tickets** · 3 open (1 escalated)", lines.get(2));
        assertEquals("**Coming up (7 days)**\n- Raid night · <t:" + RAID.toEpochSecond() + ":f>", lines.get(3));
        assertTrue(lines.get(4).startsWith("-# "));
    }

    @Test
    void linesWithNothingBehindThemAreLeftOut() {
        List<String> lines = StaffDigestRules.lines(new Facts(0, 0, 0, 0, 0, null, null, List.of()));
        assertEquals(3, lines.size());
        assertEquals("**Coming up (7 days)** · nothing scheduled", lines.get(1));
    }

    @Test
    void staleIsOnlyMentionedWhenThereIsSome() {
        assertTrue(StaffDigestRules.lines(new Facts(10, 0, 0, 2, 3, null, null, List.of())).get(1).endsWith("2 promotions due · 3 not refreshed lately"));
    }

    @Test
    void longEventListsAreCut() {
        List<EventLine> events = new ArrayList<>();
        for (int i = 0; i < StaffDigestRules.MAX_EVENTS + 3; i++) events.add(new EventLine("E" + i, RAID.plusHours(i)));
        String coming = StaffDigestRules.lines(new Facts(0, 0, 0, 0, 0, null, null, events)).get(1);
        assertEquals(StaffDigestRules.MAX_EVENTS, coming.split("\n- ").length - 1);
        assertTrue(coming.endsWith("-# …and 3 more."));
    }

    @Test
    void dueOnceOnTheChosenDayFromTheChosenHour() {
        LocalDateTime mon = LocalDateTime.of(2026, 10, 19, 9, 1);
        assertTrue(StaffDigestRules.due(mon, DayOfWeek.MONDAY, 9, null));
        assertFalse(StaffDigestRules.due(mon, DayOfWeek.MONDAY, 9, LocalDate.of(2026, 10, 19)));
        assertFalse(StaffDigestRules.due(mon, DayOfWeek.MONDAY, 10, null));
        assertFalse(StaffDigestRules.due(mon, DayOfWeek.FRIDAY, 9, null));
    }
}
