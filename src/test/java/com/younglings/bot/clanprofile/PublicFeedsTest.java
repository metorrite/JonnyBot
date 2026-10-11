package com.younglings.bot.clanprofile;

import com.younglings.bot.clanprofile.ClanLeaderboard.Entry;
import com.younglings.bot.clanprofile.ClanLeaderboard.Period;
import com.younglings.bot.clanprofile.PublicEvents.EventInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicFeedsTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private static EventInfo event(long id, String status, Duration startsIn, Duration lasts) {
        Instant start = NOW.plus(startsIn);
        return new EventInfo(id, "Event " + id, null, null, "Voice: General", status, start, lasts == null ? null : start.plus(lasts), 0, "https://discord.com/events/1/" + id);
    }

    @Test
    void upcomingMeansScheduledOrActiveAndNotOver() {
        assertTrue(PublicEvents.upcoming(event(1, "SCHEDULED", Duration.ofHours(3), Duration.ofHours(1)), NOW));
        assertTrue(PublicEvents.upcoming(event(2, "ACTIVE", Duration.ofMinutes(-30), Duration.ofHours(1)), NOW));
        assertFalse(PublicEvents.upcoming(event(3, "COMPLETED", Duration.ofHours(-5), Duration.ofHours(1)), NOW));
        assertFalse(PublicEvents.upcoming(event(4, "CANCELED", Duration.ofHours(5), Duration.ofHours(1)), NOW));
        assertFalse(PublicEvents.upcoming(event(5, "SCHEDULED", Duration.ofHours(-3), Duration.ofHours(1)), NOW), "already over");
    }

    @Test
    void anEventWithNoEndIsTreatedAsSixHoursLong() {
        assertTrue(PublicEvents.upcoming(event(1, "ACTIVE", Duration.ofHours(-5), null), NOW));
        assertFalse(PublicEvents.upcoming(event(2, "ACTIVE", Duration.ofHours(-7), null), NOW));
    }

    @Test
    void theFeedHoldsOnlySharedUpcomingEventsSoonestFirst() {
        var events = List.of(event(1, "SCHEDULED", Duration.ofDays(2), Duration.ofHours(1)), event(2, "SCHEDULED", Duration.ofHours(2), Duration.ofHours(1)),
                event(3, "SCHEDULED", Duration.ofHours(1), Duration.ofHours(1)), event(4, "CANCELED", Duration.ofHours(3), Duration.ofHours(1)));

        var feed = PublicEvents.feed(events, Set.of(1L, 2L, 4L), NOW, 10);

        assertEquals(List.of(2L, 1L), feed.stream().map(EventInfo::id).toList(), "3 is not shared and 4 is cancelled");
    }

    @Test
    void theFeedIsLimited() {
        var events = List.of(event(1, "SCHEDULED", Duration.ofHours(1), null), event(2, "SCHEDULED", Duration.ofHours(2), null), event(3, "SCHEDULED", Duration.ofHours(3), null));

        assertEquals(2, PublicEvents.feed(events, Set.of(1L, 2L, 3L), NOW, 2).size());
        assertEquals(0, PublicEvents.feed(events, Set.of(1L, 2L, 3L), NOW, 0).size());
    }

    @Test
    void periodsAreParsedAndMeasuredBackFromNow() {
        assertEquals(Period.WEEK, Period.parse("week").orElseThrow());
        assertEquals(Period.MONTH, Period.parse(" Month ").orElseThrow());
        assertTrue(Period.parse("year").isEmpty());
        assertTrue(Period.parse(null).isEmpty());
        assertEquals(NOW.minus(Duration.ofDays(7)), Period.WEEK.since(NOW));
        assertEquals(NOW.minus(Duration.ofDays(30)), Period.MONTH.since(NOW));
    }

    @Test
    void clansAreRankedByXpWithTiesBrokenByName() {
        var ranked = ClanLeaderboard.rank(List.of(new Entry("b", "Bravo", 500, 10), new Entry("a", "Alpha", 500, 20), new Entry("c", "Charlie", 900, 5), new Entry("d", "delta", 100, 1)));

        assertEquals(List.of("Charlie", "Alpha", "Bravo", "delta"), ranked.stream().map(Entry::name).toList());
    }

    @Test
    void theAveragePerMemberHandlesAnEmptyClan() {
        assertEquals(50, new Entry("a", "A", 500, 10).perMember());
        assertEquals(0, new Entry("a", "A", 500, 0).perMember());
    }
}
