package com.younglings.bot.internal;

import com.younglings.bot.internal.CockpitRules.CapWeek;
import com.younglings.bot.internal.CockpitRules.Caps;
import com.younglings.bot.internal.CockpitRules.Mark;
import com.younglings.bot.internal.CockpitRules.Member;
import com.younglings.bot.internal.CockpitRules.Rename;
import com.younglings.bot.internal.CockpitRules.RosterEvent;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The site admin's Clan health page (T-593): who goes on which list. */
class CockpitRulesTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final long DISCORD = Long.parseLong("7".repeat(18));

    private static Member member(String rsn, OffsetDateTime firstSeen, OffsetDateTime lastSeen, OffsetDateTime changed, OffsetDateTime watched, Long discord) {
        return new Member(rsn, "Recruit", firstSeen, lastSeen, changed, watched, discord);
    }

    private static Member watched(String rsn, int daysSinceChange) {
        return member(rsn, NOW.minusDays(200), NOW, NOW.minusDays(daysSinceChange), NOW.minusDays(100), null);
    }

    // ---------- gone quiet ----------

    @Test
    void quietIsNoXpChangeForTheThresholdLongestFirst() {
        List<CockpitRules.Quiet> quiet = CockpitRules.quiet(List.of(watched("Busy", 1), watched("Gone", 40), watched("Edge", 14), watched("Short", 13)), 14);
        assertEquals(List.of("Gone", "Edge"), quiet.stream().map(q -> q.member().rsn()).toList());
        assertEquals(40, quiet.getFirst().days());
        assertFalse(quiet.getFirst().atLeast());
    }

    @Test
    void neverChangedCountsFromWhenTheBotStartedWatchingAndSaysAtLeast() {
        Member never = member("Never", NOW.minusDays(300), NOW, null, NOW.minusDays(20), null);
        Member fresh = member("Fresh", NOW.minusDays(300), NOW, null, NOW.minusDays(3), null);
        Member unwatched = member("Unwatched", NOW.minusDays(300), NOW, null, null, null);
        List<CockpitRules.Quiet> quiet = CockpitRules.quiet(List.of(never, fresh, unwatched), 14);
        assertEquals(1, quiet.size());
        assertTrue(quiet.getFirst().atLeast(), "no change was ever seen: 'at least'");
        assertEquals(20, quiet.getFirst().days());
    }

    @Test
    void aRosterSyncThatStoppedNeverMakesEveryoneQuiet() {
        // XP last changed 30 days ago, but the clan Hiscores were last read 25 days ago: only 5 days are known to be quiet
        Member m = member("Paused", NOW.minusDays(300), NOW.minusDays(25), NOW.minusDays(30), NOW.minusDays(100), null);
        assertTrue(CockpitRules.quiet(List.of(m), 14).isEmpty());
        assertTrue(CockpitRules.rosterStale(NOW.minusDays(25), NOW));
        assertFalse(CockpitRules.rosterStale(NOW.minusHours(20), NOW));
        assertFalse(CockpitRules.rosterStale(null, NOW), "no roster at all isn't 'stale'");
    }

    @Test
    void theThresholdIsHeldToItsRange() {
        assertEquals(14, CockpitRules.quietDays(null));
        assertEquals(CockpitRules.MIN_QUIET_DAYS, CockpitRules.quietDays(1));
        assertEquals(CockpitRules.MAX_QUIET_DAYS, CockpitRules.quietDays(1000));
        assertEquals(21, CockpitRules.quietDays(21));
    }

    // ---------- missed caps ----------

    private static final LocalDate THIS_WEEK = LocalDate.of(2026, 10, 21); // a Wednesday

    @Test
    void theLastFourFinishedWeeksOldestFirst() {
        assertEquals(List.of(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 14)),
                CockpitRules.countedWeeks(THIS_WEEK));
    }

    @Test
    void twoMissesOfFourAreFlaggedAndWeeksWithoutDataAreLeftOut() {
        List<LocalDate> w = CockpitRules.countedWeeks(THIS_WEEK);
        Member capper = watched("Capper", 1);
        Member twice = watched("Twice", 1);
        Member once = watched("Once", 1);
        // nobody capped in week 0 (no data): Twice missed weeks 2 and 3, Once missed week 3 only
        List<CapWeek> grid = List.of(
                new CapWeek("Capper", w.get(1), true), new CapWeek("Capper", w.get(2), true), new CapWeek("Capper", w.get(3), true),
                new CapWeek("Twice", w.get(1), true), new CapWeek("Twice", w.get(2), false),
                new CapWeek("Once", w.get(1), true), new CapWeek("Once", w.get(2), true));
        Map<String, OffsetDateTime> log = Map.of("capper", NOW, "twice", NOW, "once", NOW);
        Caps caps = CockpitRules.caps(List.of(capper, twice, once), grid, log, THIS_WEEK);
        assertEquals(List.of(false, true, true, true), caps.weeks().stream().map(CockpitRules.Week::hasData).toList());
        assertEquals(1, caps.rows().size());
        assertEquals("Twice", caps.rows().getFirst().member().rsn());
        assertEquals(Arrays.asList(null, true, false, false), caps.rows().getFirst().weeks());
        assertEquals(2, caps.rows().getFirst().missed());
    }

    @Test
    void noCapDataAtAllFlagsNobody() {
        Caps caps = CockpitRules.caps(List.of(watched("A", 1), watched("B", 1)), List.of(), Map.of("a", NOW, "b", NOW), THIS_WEEK);
        assertFalse(caps.anyData());
        assertTrue(caps.rows().isEmpty(), "the Adventurer's Log feed stopping isn't everyone missing their caps");
    }

    @Test
    void aMemberWithNoLogDataIsCountedApartNotAsMissed() {
        List<LocalDate> w = CockpitRules.countedWeeks(THIS_WEEK);
        List<CapWeek> grid = List.of(new CapWeek("Capper", w.get(0), true), new CapWeek("Capper", w.get(1), true),
                new CapWeek("Capper", w.get(2), true), new CapWeek("Capper", w.get(3), true));
        Map<String, OffsetDateTime> log = new HashMap<>(Map.of("capper", NOW, "old", NOW.minusDays(90)));
        Caps caps = CockpitRules.caps(List.of(watched("Capper", 1), watched("Private", 1), watched("Old", 1)), grid, log, THIS_WEEK);
        assertTrue(caps.rows().isEmpty());
        assertEquals(2, caps.noLog(), "no Log entry in the window: a private profile, most likely");
    }

    @Test
    void weeksBeforeAMemberJoinedDontCount() {
        List<LocalDate> w = CockpitRules.countedWeeks(THIS_WEEK);
        List<CapWeek> grid = List.of(new CapWeek("Capper", w.get(0), true), new CapWeek("Capper", w.get(1), true),
                new CapWeek("Capper", w.get(2), true), new CapWeek("Capper", w.get(3), true));
        Member newbie = member("Newbie", w.get(3).atStartOfDay().atOffset(ZoneOffset.UTC).minusDays(1), NOW, null, NOW.minusDays(8), null);
        Caps caps = CockpitRules.caps(List.of(watched("Capper", 1), newbie), grid, Map.of("capper", NOW, "newbie", NOW), THIS_WEEK);
        assertTrue(caps.rows().isEmpty(), "one counted week missed is normal");
    }

    // ---------- new joins not linked ----------

    @Test
    void newJoinsNotLinkedNewestFirst() {
        Member linked = member("Linked", NOW.minusDays(3), NOW, null, NOW.minusDays(3), DISCORD);
        Member a = member("Alpha", NOW.minusDays(3), NOW, null, NOW.minusDays(3), null);
        Member b = member("Bravo", NOW.minusDays(10), NOW, null, NOW.minusDays(10), null);
        Member old = member("Old", NOW.minusDays(60), NOW, null, NOW.minusDays(60), null);
        List<RosterEvent> events = List.of(new RosterEvent("Linked", "JOIN", NOW.minusDays(3)), new RosterEvent("alpha", "JOIN", NOW.minusDays(3)),
                new RosterEvent("Bravo", "JOIN", NOW.minusDays(10)), new RosterEvent("Old", "JOIN", NOW.minusDays(60)));
        assertEquals(List.of("Alpha", "Bravo"), CockpitRules.unlinked(List.of(linked, a, b, old), events, NOW).stream().map(j -> j.member().rsn()).toList());
    }

    // ---------- left this week ----------

    @Test
    void leavesThisWeekWithoutRejoinsOrConfirmedRenames() {
        List<RosterEvent> events = List.of(new RosterEvent("Gone", "LEAVE", NOW.minusDays(2)), new RosterEvent("Back", "LEAVE", NOW.minusDays(3)),
                new RosterEvent("Renamed", "LEAVE", NOW.minusDays(1)), new RosterEvent("Maybe", "LEAVE", NOW.minusDays(1)),
                new RosterEvent("LongAgo", "LEAVE", NOW.minusDays(9)));
        List<Rename> renames = List.of(new Rename("Renamed", "NewName", "CONFIRMED"), new Rename("Maybe", "Other", "PENDING"));
        List<CockpitRules.Leaver> left = CockpitRules.left(List.of(watched("Back", 1)), events, renames, Map.of("gone", DISCORD), NOW);
        assertEquals(List.of("Maybe", "Gone"), left.stream().map(CockpitRules.Leaver::rsn).toList());
        assertEquals("Other", left.getFirst().maybeRenamedTo());
        assertEquals(DISCORD, left.get(1).discordId());
    }

    // ---------- done marks ----------

    @Test
    void aMarkCountsForTwoWeeksAndOnlyForTheSameStreak() {
        Mark mark = new Mark("quiet", "gone", DISCORD, "Officer", NOW.minusDays(3));
        Map<String, Mark> marks = CockpitRules.byKey(List.of(mark));
        assertNotNull(CockpitRules.activeMark(marks, "quiet", "gone", NOW.minusDays(20), NOW));
        assertNull(CockpitRules.activeMark(marks, "quiet", "gone", NOW.minusDays(1), NOW), "quiet again since the mark: a new look");
        assertNull(CockpitRules.activeMark(marks, "caps", "gone", null, NOW), "a mark is per list");
        Map<String, Mark> old = CockpitRules.byKey(List.of(new Mark("caps", "gone", DISCORD, null, NOW.minusDays(CockpitRules.MARK_DAYS + 1))));
        assertNull(CockpitRules.activeMark(old, "caps", "gone", null, NOW));
        DataObject json = SiteCockpitAdmin.markJson(mark);
        assertEquals(Long.toString(DISCORD), json.getString("by"), "a Discord id goes out as a string");
        assertEquals("Officer", json.getString("byName"));
    }

    @Test
    void aMarkNamesARuneScapeName() {
        assertTrue(CockpitRules.plausibleName("Zezima"));
        assertTrue(CockpitRules.plausibleName("A b_c-1"));
        assertFalse(CockpitRules.plausibleName(""));
        assertFalse(CockpitRules.plausibleName("x".repeat(13)));
        assertFalse(CockpitRules.plausibleName("<script>"));
        assertFalse(CockpitRules.plausibleName(null));
    }
}
