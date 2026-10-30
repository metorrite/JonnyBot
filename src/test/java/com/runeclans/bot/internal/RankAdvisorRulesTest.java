package com.runeclans.bot.internal;

import com.runeclans.bot.internal.CockpitRules.CapWeek;
import com.runeclans.bot.internal.RankAdvisorRules.Due;
import com.runeclans.bot.internal.RankAdvisorRules.Facts;
import com.runeclans.bot.internal.RankAdvisorRules.Hold;
import com.runeclans.bot.internal.RankAdvisorRules.Promoted;
import com.runeclans.bot.internal.RankAdvisorRules.Ready;
import com.runeclans.bot.internal.RankAdvisorRules.Rule;
import com.runeclans.bot.internal.RankAdvisorRules.Tick;
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

    private static List<String> names(List<Ready> ready) {
        return ready.stream().map(x -> x.member().rsn()).toList();
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
        assertEquals(RankAdvisorRules.MAX_CONGRATS, RankAdvisorRules.cleanCongrats("x".repeat(1500)).length());
        List<Promoted> two = List.of(new Promoted("zezima", "Zezima", "Recruit", "Corporal", "Zez"), new Promoted("b_ob", "B_ob", "Corporal", "Sergeant", null), new Promoted("x", "X", "Recruit", "Corporal", "**Loud**"));
        assertEquals("Well done, 3 of you!\n• @Zez (Zezima): Corporal\n• B_ob: Sergeant\n• @\\*\\*Loud\\*\\* (X): Corporal",
                RankAdvisorRules.weeklyText("Well done, {count} of you!\n{list}", two), "Discord names and RuneScape names, markdown escaped");
        assertEquals("Nice work\n• @Zez (Zezima): Corporal", RankAdvisorRules.weeklyText("Nice work", two.subList(0, 1)), "no {list}: the list goes under");
        assertEquals("", RankAdvisorRules.weeklyText(null, List.of()), "nobody promoted: no message");
        assertTrue(RankAdvisorRules.weeklyText(null, two).startsWith("Congratulations to everyone promoted this week!\n• @Zez"));
    }

    @Test
    void aMemberNamedEveryoneNeverPingsTheServerFromTheCopiedText() {
        String line = RankAdvisorRules.congratsLine(new Promoted("a", "A", "Recruit", "Corporal", "everyone"));
        assertEquals("• @\u200beveryone (A): Corporal", line);
        String inside = RankAdvisorRules.congratsLine(new Promoted("b", "B", "Recruit", "Corporal", "hi @Here"));
        assertFalse(inside.toLowerCase().contains("@here"), inside);
        assertFalse(line.contains("@everyone"));
    }

    @Test
    void missingDiscordNamesAreFilledInWhenShown() {
        List<Promoted> week = List.of(new Promoted("a", "A", "Recruit", "Corporal", null), new Promoted("b", "B", "Recruit", "Corporal", "Kept"));
        List<Promoted> named = RankAdvisorRules.withNames(week, Map.of("a", "Now Known", "b", "Other"));
        assertEquals("Now Known", named.get(0).discordName(), "a turn without Discord's cache stored no name");
        assertEquals("Kept", named.get(1).discordName(), "a stored name stays");
        assertNull(RankAdvisorRules.withNames(week, Map.of()).get(0).discordName());
    }

    @Test
    void anEmptyOrPartialRosterKeepsEveryoneDue() {
        LocalDate week = LocalDate.of(2026, 10, 28);
        List<Due> due = List.of(new Due("a", "A", "Recruit", "Corporal", week.minusWeeks(1)), new Due("b", "B", "Recruit", "Corporal", week.minusWeeks(1)));
        RankAdvisorRules.Turn none = RankAdvisorRules.turn(week, due, List.of(), List.of(), java.util.Set.of(), Map.of());
        assertTrue(none.promoted().isEmpty());
        assertTrue(none.drop().isEmpty(), "a roster sync outage must not wipe the due list");
        RankAdvisorRules.Turn part = RankAdvisorRules.turn(week, due, List.of(member("A", "Corporal", 0L, 30, null, 0)), List.of(), java.util.Set.of(), Map.of());
        assertEquals(List.of("a"), part.drop().stream().map(Due::rsnKey).toList(), "B, missing this time, stays due for next week");
    }

    @Test
    void theStaffCheckIsOneShortLine() {
        assertNull(RankAdvisorRules.cleanStaffCheck("   "));
        assertEquals("Master CAs done", RankAdvisorRules.cleanStaffCheck(" Master\nCAs   done "));
        assertEquals(RankAdvisorRules.MAX_STAFF_CHECK, RankAdvisorRules.cleanStaffCheck("y".repeat(300)).length());
        assertTrue(new Rule("Corporal", null, null, null, null, "Elite CAs").empty(), "a staff check alone checks nothing");
        assertFalse(new Rule("Corporal", null, null, null, null, "Elite CAs").blank(), "but it is saved");
    }

    @Test
    void longMessagesAreCutAtLineEnds() {
        List<String> parts = RankAdvisorRules.chunks("aaaa\nbbbb\ncc", 9);
        assertEquals(List.of("aaaa\nbbbb", "cc"), parts);
        assertEquals(List.of("abcde", "fgh"), RankAdvisorRules.chunks("abcdefgh", 5), "one over-long line is cut where it must be");
        String big = String.join("\n", java.util.Collections.nCopies(300, "• @Someone Long (Some Rsn): Lieutenant"));
        for (String p : RankAdvisorRules.chunks(big, RankAdvisorRules.DISCORD_MAX)) assertTrue(p.length() <= RankAdvisorRules.DISCORD_MAX);
        assertEquals(big, String.join("\n", RankAdvisorRules.chunks(big, RankAdvisorRules.DISCORD_MAX)), "nothing is lost");
    }

    @Test
    void heldMembersLeaveTheReadyListAndPendingIsLowestRankFirst() {
        Map<String, Rule> r = rules(new Rule("Corporal", null, 10, null, null, "Elite CAs"), new Rule("Captain", null, 10, null, null));
        List<Facts> members = List.of(member("Low", "Recruit", 0L, 30, null, 0), member("High", "Lieutenant", 0L, 30, null, 0),
                member("Alt", "Recruit", 0L, 30, null, 0), member("Fresh", "Recruit", 0L, 2, null, 0));
        List<Hold> holds = List.of(new Hold("high", "High", RankAdvisorRules.PENDING, STAFF, "Mod", NOW),
                new Hold("low", "Low", RankAdvisorRules.PENDING, STAFF, "Mod", NOW), new Hold("alt", "Alt", RankAdvisorRules.IGNORED, STAFF, "Mod", NOW),
                new Hold("fresh", "Fresh", RankAdvisorRules.PENDING, STAFF, "Mod", NOW));
        java.util.Set<String> held = java.util.Set.of("high", "low", "alt", "fresh");
        assertTrue(RankAdvisorRules.ready(members, r, List.of(), NOW, true, held).isEmpty(), "held members are listed apart");
        assertEquals(List.of("Alt", "Low"), names(RankAdvisorRules.ready(members, r, List.of(), NOW, true, java.util.Set.of("high", "fresh"))));
        List<RankAdvisorRules.Pending> pending = RankAdvisorRules.pending(members, r, List.of(), holds, NOW, true);
        assertEquals(List.of("Fresh", "Low", "High"), pending.stream().map(p -> p.member().rsn()).toList(), "lowest current rank first, ignored left out");
        assertFalse(pending.get(0).meets(), "on the list without meeting the numbers yet");
        assertTrue(pending.get(1).meets());
        assertEquals("Captain", pending.get(2).next().target());
    }

    @Test
    void theWeekTurnsTwelveHoursAfterTheCitadelReset() {
        // 2026-10-28 is a Wednesday
        assertEquals(LocalDate.of(2026, 10, 21), RankAdvisorRules.weekOf(OffsetDateTime.of(2026, 10, 28, 11, 59, 0, 0, ZoneOffset.UTC)));
        assertEquals(LocalDate.of(2026, 10, 28), RankAdvisorRules.weekOf(OffsetDateTime.of(2026, 10, 28, 12, 0, 0, 0, ZoneOffset.UTC)));
        assertEquals(LocalDate.of(2026, 10, 28), RankAdvisorRules.weekOf(OffsetDateTime.of(2026, 11, 4, 11, 0, 0, 0, ZoneOffset.UTC)));
        assertEquals(LocalDate.of(2026, 10, 28), RankAdvisorRules.weekOf(OffsetDateTime.of(2026, 11, 3, 20, 0, 0, 0, ZoneOffset.ofHours(-5))),
                "any offset reads as UTC");
    }

    @Test
    void theTurnCongratulatesWhoWasDueAndGotTheRank() {
        LocalDate week = LocalDate.of(2026, 10, 28);
        LocalDate last = week.minusWeeks(1);
        List<Facts> members = List.of(member("Got It", "Corporal", 0L, 30, null, 0), member("Not Yet", "Recruit", 0L, 30, null, 0),
                member("Ticked", "Corporal", 0L, 30, null, 0), member("Jumped", "Lieutenant", 0L, 30, null, 0),
                member("Alt", "Corporal", 0L, 30, null, 0), member("Stale", "Recruit", 0L, 300, null, 0), member("New Due", "Corporal", 0L, 30, null, 0));
        List<Due> due = List.of(new Due("got it", "Got It", "Recruit", "Corporal", last), new Due("not yet", "Not Yet", "Recruit", "Corporal", last),
                new Due("ticked", "Ticked", "Corporal", "Sergeant", last), new Due("jumped", "Jumped", "Corporal", "Sergeant", last.minusWeeks(2)),
                new Due("alt", "Alt", "Recruit", "Corporal", last), new Due("left", "Left", "Recruit", "Corporal", last),
                new Due("stale", "Stale", "Recruit", "Corporal", week.minusWeeks(RankAdvisorRules.DUE_KEEP_WEEKS + 1)),
                new Due("new due", "New Due", "Recruit", "Corporal", week));
        List<Tick> ticks = List.of(new Tick("ticked", "Sergeant", STAFF, "Mod", NOW));
        RankAdvisorRules.Turn turn = RankAdvisorRules.turn(week, due, members, ticks, java.util.Set.of("alt"), Map.of("got it", "GotItDiscord"));
        assertEquals(List.of("Jumped:Lieutenant", "Ticked:Sergeant", "Got It:Corporal"),
                turn.promoted().stream().map(p -> p.rsn() + ":" + p.toRank()).toList(), "highest rank first; the in-game rank when higher than due");
        assertEquals("GotItDiscord", turn.promoted().get(2).discordName());
        List<String> dropped = turn.drop().stream().map(Due::rsnKey).sorted().toList();
        assertEquals(List.of("alt", "got it", "jumped", "stale", "ticked"), dropped,
                "not yet stays due; someone missing from the roster ages out; this week's entries wait for next week");
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

        List<String> fine = new ArrayList<>();
        List<Rule> withCheck = SiteRankAdvisor.parseRules(DataObject.fromJson("""
                {"rules": [{"rank": "General", "staffCheck": "  Master CAs  "}, {"rank": "Captain", "minDays": 5, "staffCheck": null}]}
                """), fine);
        assertEquals(List.of(), fine);
        assertEquals("Master CAs", withCheck.get(0).staffCheck());
        assertNull(withCheck.get(1).staffCheck());
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
        assertTrue(json.isNull("staffCheck"));
        assertEquals("Elite CAs", SiteRankAdvisor.ruleJson(new Rule("General", null, 30, null, null, "Elite CAs")).getString("staffCheck"));
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
