package com.younglings.bot.events;

import com.younglings.bot.events.PrizePlan.Award;
import com.younglings.bot.events.PrizePlan.Place;
import com.younglings.bot.events.PrizePlan.Prize;
import com.younglings.bot.events.PrizePlan.Split;
import com.younglings.bot.events.PrizeRules.PrizeKind;
import com.younglings.bot.events.TeamRules.Kind;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSettingsAndPrizePlanTest {
    private static Prize prize(long id, int from, int to, PrizeKind kind, long amount, int percent, Split split) {
        return new Prize(id, from, to, kind, kind == PrizeKind.IN_GAME ? "Item " + id : "", amount, percent, split);
    }

    private static Place solo(long user) {
        return new Place("u:" + user, List.of(user));
    }

    private static Place team(long id, Long... members) {
        return new Place("t:" + id, List.of(members));
    }

    // ---------- settings ----------

    @Test
    void noSettingsMeansTheDefaults() {
        var s = EventSettings.parse(DataObject.empty());
        assertFalse(s.buyIn().enabled());
        assertEquals(PrizeKind.IN_GAME, s.buyIn().kind(), "gp is the default currency");
        assertEquals(QueueRules.Policy.FIRST_COME, s.queue());
        assertEquals(TeamRules.everyoneTogether(), s.teamPolicy());
        assertTrue(s.selfSignupOpen(Instant.now()));
        assertTrue(EventSettings.problems(DataObject.empty()).isEmpty());
    }

    @Test
    void settingsAreReadAndChecked() {
        var config = DataObject.fromJson("{\"buyIn\":{\"enabled\":true,\"amount\":5000000,\"showPot\":true},\"teamPolicy\":\"clanVsOutsiders\",\"queue\":\"CLAN_FIRST\",\"signupClosesAt\":\"2026-10-20T19:00:00Z\"}");
        var s = EventSettings.parse(config);
        assertTrue(s.buyIn().enabled());
        assertEquals(5_000_000, s.buyIn().amount());
        assertEquals(PrizeKind.IN_GAME, s.buyIn().kind());
        assertTrue(s.buyIn().showPot());
        assertEquals(TeamRules.clanVersusOutsiders(), s.teamPolicy());
        assertEquals(QueueRules.Policy.CLAN_FIRST, s.queue());
        assertTrue(EventSettings.problems(config).isEmpty());
        assertTrue(s.selfSignupOpen(Instant.parse("2026-10-20T18:59:59Z")));
        assertFalse(s.selfSignupOpen(Instant.parse("2026-10-20T19:00:00Z")), "closed at the deadline");

        assertFalse(EventSettings.problems(DataObject.fromJson("{\"buyIn\":{\"enabled\":true,\"amount\":0}}")).isEmpty(), "a buy-in needs an amount");
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"buyIn\":{\"enabled\":true,\"amount\":5,\"kind\":\"bananas\"}}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"teamPolicy\":\"chaos\"}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"queue\":\"random\"}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"signupClosesAt\":\"next friday\"}")).isEmpty());
    }

    // ---------- prize plans ----------

    @Test
    void aSingleWinnerGetsTheOnlyPrize() {
        var awards = PrizePlan.plan(List.of(prize(1, 1, 1, PrizeKind.IN_GAME, 0, 0, Split.EQUAL)), List.of(solo(10), solo(11), solo(12)), 0);
        assertEquals(1, awards.size());
        assertEquals(10, awards.get(0).userId());
        assertEquals(1, awards.get(0).place());
    }

    @Test
    void aRangeOfPlacesEachGetThePrize() {
        var awards = PrizePlan.plan(List.of(prize(1, 2, 4, PrizeKind.CLAN_POINTS, 100, 0, Split.EQUAL)), List.of(solo(10), solo(11), solo(12), solo(13), solo(14)), 0);
        assertEquals(List.of(11L, 12L, 13L), awards.stream().map(Award::userId).toList());
        assertTrue(awards.stream().allMatch(a -> a.amount() == 100));
    }

    @Test
    void placesBeyondTheStandingsGetNothing() {
        var awards = PrizePlan.plan(List.of(prize(1, 1, 5, PrizeKind.CLAN_POINTS, 100, 0, Split.EQUAL)), List.of(solo(10), solo(11)), 0);
        assertEquals(2, awards.size());
    }

    @Test
    void aTeamPrizeCanBeSplitEquallyWithTheLeftoverToTheCaptain() {
        var awards = PrizePlan.plan(List.of(prize(1, 1, 1, PrizeKind.CLAN_POINTS, 100, 0, Split.EQUAL)), List.of(team(1, 5L, 6L, 7L)), 0);
        assertEquals(3, awards.size());
        assertEquals(34, awards.get(0).amount(), "100 over three is 33 each and the odd one goes to the captain");
        assertEquals(33, awards.get(1).amount());
        assertEquals(100, awards.stream().mapToLong(Award::amount).sum(), "nothing is lost");
    }

    @Test
    void aTeamPrizeCanGoInFullToEachMemberOrOnlyToTheCaptain() {
        var each = PrizePlan.plan(List.of(prize(1, 1, 1, PrizeKind.IN_GAME, 0, 0, Split.EACH)), List.of(team(1, 5L, 6L)), 0);
        assertEquals(2, each.size());
        var captain = PrizePlan.plan(List.of(prize(1, 1, 1, PrizeKind.IN_GAME, 0, 0, Split.CAPTAIN)), List.of(team(1, 5L, 6L)), 0);
        assertEquals(1, captain.size());
        assertEquals(5, captain.get(0).userId());
    }

    @Test
    void prizesCanBeSharesOfThePot() {
        var prizes = List.of(prize(1, 1, 1, PrizeKind.IN_GAME, 0, 5_000, Split.EQUAL), prize(2, 2, 2, PrizeKind.IN_GAME, 0, 3_000, Split.EQUAL), prize(3, 3, 3, PrizeKind.IN_GAME, 0, 2_000, Split.EQUAL));
        var awards = PrizePlan.plan(prizes, List.of(solo(1), solo(2), solo(3)), 10_000_000);
        assertEquals(List.of(5_000_000L, 3_000_000L, 2_000_000L), awards.stream().map(Award::amount).toList());
        assertTrue(PrizePlan.setProblems(prizes).isEmpty());
        assertFalse(PrizePlan.setProblems(List.of(prize(1, 1, 1, PrizeKind.IN_GAME, 0, 7_000, Split.EQUAL), prize(2, 2, 2, PrizeKind.IN_GAME, 0, 4_000, Split.EQUAL))).isEmpty(), "110% of the pot");
    }

    @Test
    void soloAndTeamPlacesAreTreatedAlike() {
        var mixed = PrizePlan.plan(List.of(prize(1, 1, 2, PrizeKind.CLAN_POINTS, 60, 0, Split.EQUAL)), List.of(team(1, 5L, 6L), solo(9)), 0);
        assertEquals(List.of(30L, 30L, 60L), mixed.stream().map(Award::amount).toList());
    }

    @Test
    void prizesAreValidated() {
        assertTrue(PrizePlan.problems(prize(1, 1, 3, PrizeKind.IN_GAME, 0, 0, Split.EQUAL)).isEmpty());
        assertFalse(PrizePlan.problems(prize(1, 0, 0, PrizeKind.IN_GAME, 0, 0, Split.EQUAL)).isEmpty(), "needs a place");
        assertFalse(PrizePlan.problems(prize(1, 3, 2, PrizeKind.IN_GAME, 0, 0, Split.EQUAL)).isEmpty(), "range runs backwards");
        assertFalse(PrizePlan.problems(prize(1, 1, 1, PrizeKind.CLAN_POINTS, 0, 0, Split.EQUAL)).isEmpty(), "points need an amount");
        assertFalse(PrizePlan.problems(prize(1, 1, 1, PrizeKind.CLAN_POINTS, 100, 5_000, Split.EQUAL)).isEmpty(), "points can't be a share of a gp pot");
        assertFalse(PrizePlan.problems(prize(1, 1, 1, PrizeKind.CLAN_POINTS, 2_000_000, 0, Split.EQUAL)).isEmpty());
        assertFalse(PrizePlan.problems(new Prize(1, 1, 1, PrizeKind.IN_GAME, "x", 0, 0, null)).isEmpty());
        assertEquals(Kind.HOME_CLAN, Kind.valueOf("HOME_CLAN"));
    }
}
