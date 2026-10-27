package com.younglings.bot.internal;

import com.younglings.bot.internal.CockpitRules.CapWeek;
import com.younglings.bot.internal.RankAdvisorRules.Facts;
import com.younglings.bot.internal.RankAdvisorRules.Ready;
import com.younglings.bot.internal.RankAdvisorRules.Rule;
import com.younglings.bot.internal.RankAdvisorRules.Tick;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rank-up advisor's pure rules (T-594): who meets the rules for their next rank, what a tick hides, and the owner's edits. */
class RankAdvisorRulesTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 27, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final long STAFF = Long.parseLong("7".repeat(18));

    private static Facts member(String rsn, String rank, Long xp, int daysIn, Integer caps, long points) {
        return new Facts(rsn, rank, xp, NOW.minusDays(daysIn), NOW.minusDays(daysIn), caps, points);
    }

    private static Map<String, Rule> rules(Rule... rules) {
        Map<String, Rule> out = new java.util.LinkedHashMap<>();
        for (Rule r : rules) out.put(r.rank(), r);
        return out;
    }

    @Test
    void theLadderIsTheGamesRanksAndRulesSkipRecruitAndOwner() {
        assertEquals("Recruit", RankAdvisorRules.LADDER.getFirst());
        assertEquals("Owner", RankAdvisorRules.LADDER.getLast());
        assertFalse(RankAdvisorRules.TARGETS.contains("Recruit"));
        assertFalse(RankAdvisorRules.TARGETS.contains("Owner"));
        assertEquals(RankAdvisorRules.LADDER.size() - 2, RankAdvisorRules.TARGETS.size());
        assertEquals("Deputy Owner", RankAdvisorRules.canonical("deputy owner"));
        assertEquals(-1, RankAdvisorRules.indexOf("Banana"));
        assertTrue(RankAdvisorRules.isTarget("corporal"));
    }

    @Test
    void someoneMeetingEveryPartOfTheNextRanksRuleIsListed() {
        Map<String, Rule> r = rules(new Rule("Corporal", 1_000_000L, 14, 2, 50L));
        List<Ready> ready = RankAdvisorRules.ready(List.of(
                member("Ready One", "Recruit", 2_000_000L, 30, 3, 60),
                member("Short Xp", "Recruit", 10L, 30, 3, 60),
                member("Too New", "Recruit", 2_000_000L, 3, 3, 60),
                member("No Log", "Recruit", 2_000_000L, 30, null, 60),
                member("Few Pts", "Recruit", 2_000_000L, 30, 3, 10),
                member("Unknown Xp", "Recruit", null, 30, 3, 60)), r, List.of(), NOW);
        assertEquals(List.of("Ready One"), ready.stream().map(x -> x.member().rsn()).toList());
        assertEquals("Corporal", ready.getFirst().target());
        assertEquals(4, ready.getFirst().checks().size());
        assertTrue(ready.getFirst().checks().stream().allMatch(RankAdvisorRules.Check::met));
    }

    @Test
    void aRankWithNoRuleListsNobodyAndPartsLeftEmptyArentChecked() {
        Map<String, Rule> r = rules(new Rule("Sergeant", null, 30, null, null));
        List<Ready> ready = RankAdvisorRules.ready(List.of(
                member("Corp", "Corporal", null, 40, null, 0),
                member("Recruit", "Recruit", 9_000_000L, 400, 12, 9_999)), r, List.of(), NOW);
        assertEquals(List.of("Corp"), ready.stream().map(x -> x.member().rsn()).toList(), "Corporal's rule is unset, so the recruit isn't listed");
        assertEquals(1, ready.getFirst().checks().size());
    }

    @Test
    void aTickHidesThemUntilTheyMeetTheRuleForTheRankAfter() {
        Map<String, Rule> r = rules(new Rule("Corporal", null, 7, null, null), new Rule("Sergeant", null, 60, null, null));
        Facts m = member("Ticked", "Recruit", null, 30, null, 0);
        List<Tick> ticks = List.of(new Tick("ticked", "Corporal", STAFF, "Staff", NOW));
        assertTrue(RankAdvisorRules.ready(List.of(m), r, ticks, NOW).isEmpty(), "ticked for Corporal; not yet 60 days for Sergeant");
        Facts later = member("Ticked", "Recruit", null, 61, null, 0);
        List<Ready> ready = RankAdvisorRules.ready(List.of(later), r, ticks, NOW);
        assertEquals("Sergeant", ready.getFirst().target(), "the game still shows Recruit, but staff ticked Corporal");
        Facts ranked = member("Ticked", "Corporal", null, 61, null, 0);
        assertEquals("Sergeant", RankAdvisorRules.ready(List.of(ranked), r, ticks, NOW).getFirst().target(), "an old tick below the rank is ignored");
    }

    @Test
    void xpGainedAtTheOldRankDoesntCountTowardTheRankAfterATick() {
        Map<String, Rule> r = rules(new Rule("Corporal", 1_000_000L, null, null, null), new Rule("Sergeant", 1_000_000L, null, null, null));
        List<Tick> ticks = List.of(new Tick("big xp", "Corporal", STAFF, "Staff", NOW));
        Facts waiting = member("Big Xp", "Recruit", 50_000_000L, 30, null, 0);
        assertTrue(RankAdvisorRules.ready(List.of(waiting), r, ticks, NOW).isEmpty(),
                "ticked for Corporal while the game still shows Recruit: the Recruit XP can't make them ready for Sergeant");
        Facts shown = member("Big Xp", "Corporal", 2_000_000L, 30, null, 0);
        assertEquals("Sergeant", RankAdvisorRules.ready(List.of(shown), r, ticks, NOW).getFirst().target(),
                "once the game shows Corporal, XP at Corporal counts");
    }

    @Test
    void ownersAndUnknownRanksAreNeverListedAndHigherTargetsComeFirst() {
        Map<String, Rule> r = rules(new Rule("Corporal", null, 1, null, null), new Rule("General", null, 1, null, null));
        List<Ready> ready = RankAdvisorRules.ready(List.of(
                member("Boss", "Owner", null, 900, null, 0),
                member("Odd", "Banana", null, 900, null, 0),
                member("Rec", "Recruit", null, 900, null, 0),
                member("Cap", "Captain", null, 900, null, 0)), r, List.of(), NOW);
        assertEquals(List.of("Cap", "Rec"), ready.stream().map(x -> x.member().rsn()).toList());
    }

    @Test
    void capsCountTheWindowAndNoLogIsUnknown() {
        LocalDate week = RecapPeriod.citadelWeekStart(NOW.toLocalDate());
        List<CapWeek> grid = new ArrayList<>();
        grid.add(new CapWeek("Capper", week, true));
        grid.add(new CapWeek("Capper", week.minusWeeks(3), true));
        grid.add(new CapWeek("capper", week.minusWeeks(3), true));
        grid.add(new CapWeek("Capper", week.minusWeeks(RankAdvisorRules.CAP_WINDOW_WEEKS), true));
        grid.add(new CapWeek("Visitor", week, false));
        Map<String, Integer> caps = RankAdvisorRules.capsByMember(grid, week);
        assertEquals(2, caps.get("capper"), "the same week once, and the week before the window left out");
        Map<String, OffsetDateTime> logs = Map.of("visitor", NOW.minusDays(2), "stale", NOW.minusDays(400));
        assertEquals(2, RankAdvisorRules.capsOf("capper", caps, logs, week));
        assertEquals(0, RankAdvisorRules.capsOf("visitor", caps, logs, week), "a log in the window and no cap is 0");
        assertNull(RankAdvisorRules.capsOf("stale", caps, logs, week), "no log in the window is unknown");
        assertNull(RankAdvisorRules.capsOf("nobody", caps, logs, week));
    }

    @Test
    void theCongratsTextIsCleanedAndFilledIn() {
        assertEquals(RankAdvisorRules.DEFAULT_CONGRATS, RankAdvisorRules.cleanCongrats("   "));
        assertEquals("Hi\nthere", RankAdvisorRules.cleanCongrats(" Hi\r\nthere\u0007 "));
        assertEquals(RankAdvisorRules.MAX_CONGRATS, RankAdvisorRules.cleanCongrats("x".repeat(500)).length());
        assertEquals("Well done Zezima, now a Sergeant", RankAdvisorRules.congratsText("Well done {name}, now a {rank}", "Zezima", "Sergeant"));
        assertEquals("Congratulations Zezima on your promotion to Corporal!", RankAdvisorRules.congratsText(null, "Zezima", "Corporal"));
    }

    @Test
    void savedRulesAreCheckedPartByPart() {
        List<String> problems = new ArrayList<>();
        DataObject body = DataObject.fromJson("""
                {"rules": [
                  {"rank": "corporal", "minXp": 1000000, "minDays": 14, "minCaps": null, "minPoints": 5},
                  {"rank": "Owner", "minDays": 1},
                  {"rank": "Sergeant", "minCaps": 13},
                  {"rank": "Lieutenant", "minDays": -1},
                  {"rank": "Captain", "minXp": 1.5},
                  {"rank": "Corporal", "minDays": 3}
                ]}
                """);
        List<Rule> rules = SiteRankAdvisor.parseRules(body, problems);
        assertEquals(1, rules.size());
        assertEquals(new Rule("Corporal", 1_000_000L, 14, null, 5L), rules.getFirst());
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Owner")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("Corporal has two rules")));

        List<String> none = new ArrayList<>();
        SiteRankAdvisor.parseRules(DataObject.fromJson("{}"), none);
        assertEquals(1, none.size());
    }

    @Test
    void ruleJsonSendsEveryPart() {
        DataObject json = SiteRankAdvisor.ruleJson(new Rule("General", null, 30, 4, null));
        assertEquals("General", json.getString("rank"));
        assertTrue(json.isNull("minXp"));
        assertEquals(30, json.getInt("minDays"));
        assertEquals(4, json.getInt("minCaps"));
    }

    @Test
    void numbersOutsideTheirRangeAreProblems() {
        List<String> problems = new ArrayList<>();
        assertNull(RankAdvisorRules.number(null, 10, "x", problems));
        assertEquals(10L, RankAdvisorRules.number(10L, 10, "x", problems));
        assertNull(RankAdvisorRules.number(11L, 10, "x", problems));
        assertNull(RankAdvisorRules.number("5", 10, "x", problems));
        assertEquals(2, problems.size());
    }
}
