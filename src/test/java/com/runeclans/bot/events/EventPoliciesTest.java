package com.runeclans.bot.events;

import com.runeclans.bot.events.PrizeRules.AwardStatus;
import com.runeclans.bot.events.PrizeRules.BuyIn;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.PrizeRules.Prize;
import com.runeclans.bot.events.PrizeRules.PrizeKind;
import com.runeclans.bot.events.QueueRules.Entry;
import com.runeclans.bot.events.TeamRules.Kind;
import com.runeclans.bot.events.TeamRules.Policy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventPoliciesTest {
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private static Entry entry(long user, Kind kind, int minutes) {
        return new Entry(user, kind, T0.plusSeconds(minutes * 60L));
    }

    // ---------- team mixing ----------

    @Test
    void presetsBehaveAsDescribed() {
        Policy together = TeamRules.everyoneTogether();
        assertTrue(TeamRules.mayShareTeam(together, Kind.PARTNER, List.of(Kind.HOME_CLAN, Kind.HOME_OTHER)));

        Policy homeOnly = TeamRules.homeServerOnly();
        assertFalse(TeamRules.mayJoin(homeOnly, Kind.PARTNER));
        assertTrue(TeamRules.mayShareTeam(homeOnly, Kind.HOME_OTHER, List.of(Kind.HOME_CLAN)));

        Policy versus = TeamRules.clanVersusOutsiders();
        assertFalse(TeamRules.mayShareTeam(versus, Kind.HOME_CLAN, List.of(Kind.PARTNER)), "clan members only with clan members");
        assertTrue(TeamRules.mayShareTeam(versus, Kind.HOME_OTHER, List.of(Kind.PARTNER)), "outsiders mix with each other");
        assertTrue(TeamRules.mayShareTeam(versus, Kind.HOME_CLAN, List.of(Kind.HOME_CLAN)));

        Policy separate = TeamRules.allSeparate();
        assertFalse(TeamRules.mayShareTeam(separate, Kind.HOME_OTHER, List.of(Kind.HOME_CLAN)));
        assertTrue(TeamRules.mayShareTeam(separate, Kind.PARTNER, List.of(Kind.PARTNER, Kind.PARTNER)));
    }

    @Test
    void anEmptyTeamAcceptsAnyAllowedKind() {
        assertTrue(TeamRules.mayShareTeam(TeamRules.allSeparate(), Kind.HOME_OTHER, List.of()));
        assertFalse(TeamRules.mayShareTeam(TeamRules.homeServerOnly(), Kind.PARTNER, List.of()), "not allowed to join at all");
    }

    @Test
    void aKindInNoGroupOnlyTeamsWithItsOwnKind() {
        Policy loner = new Policy(EnumSet.allOf(Kind.class), List.of(EnumSet.of(Kind.HOME_CLAN, Kind.HOME_OTHER)));
        assertFalse(TeamRules.mayShareTeam(loner, Kind.PARTNER, List.of(Kind.HOME_OTHER)));
        assertTrue(TeamRules.mayShareTeam(loner, Kind.PARTNER, List.of(Kind.PARTNER)));
    }

    @Test
    void policiesAreChecked() {
        assertTrue(TeamRules.problems(TeamRules.everyoneTogether()).isEmpty());
        assertTrue(TeamRules.problems(TeamRules.clanVersusOutsiders()).isEmpty());
        assertFalse(TeamRules.problems(new Policy(EnumSet.noneOf(Kind.class), List.of())).isEmpty());
        assertFalse(TeamRules.problems(new Policy(EnumSet.allOf(Kind.class), List.of(EnumSet.of(Kind.HOME_CLAN), EnumSet.of(Kind.HOME_CLAN)))).isEmpty(), "in two groups");
        assertFalse(TeamRules.problems(new Policy(EnumSet.of(Kind.HOME_CLAN), List.of(EnumSet.of(Kind.PARTNER)))).isEmpty(), "grouped but not allowed");
    }

    // ---------- the waitlist ----------

    @Test
    void firstComeFirstServedOrdersByTime() {
        var waiting = List.of(entry(3, Kind.PARTNER, 30), entry(1, Kind.HOME_CLAN, 40), entry(2, Kind.HOME_OTHER, 10));
        var order = QueueRules.order(QueueRules.Policy.FIRST_COME, waiting);
        assertEquals(List.of(2L, 3L, 1L), order.stream().map(Entry::userId).toList());
    }

    @Test
    void clanFirstPutsClanMembersAheadThenTime() {
        var waiting = List.of(entry(3, Kind.PARTNER, 30), entry(1, Kind.HOME_CLAN, 40), entry(2, Kind.HOME_OTHER, 10), entry(4, Kind.HOME_CLAN, 20));
        var order = QueueRules.order(QueueRules.Policy.CLAN_FIRST, waiting);
        assertEquals(List.of(4L, 1L, 2L, 3L), order.stream().map(Entry::userId).toList());
        assertEquals(4L, QueueRules.next(QueueRules.Policy.CLAN_FIRST, waiting).userId());
        assertEquals(2, QueueRules.position(QueueRules.Policy.CLAN_FIRST, waiting, 1L));
        assertEquals(0, QueueRules.position(QueueRules.Policy.CLAN_FIRST, waiting, 99L));
    }

    @Test
    void aFullEventSplitsPlacesFromTheWaitlist() {
        var everyone = List.of(entry(1, Kind.PARTNER, 1), entry(2, Kind.HOME_CLAN, 2), entry(3, Kind.HOME_OTHER, 3));
        var first = QueueRules.split(QueueRules.Policy.FIRST_COME, 2, everyone);
        assertEquals(List.of(1L, 2L), first.active().stream().map(Entry::userId).toList());
        assertEquals(List.of(3L), first.waiting().stream().map(Entry::userId).toList());
        var clan = QueueRules.split(QueueRules.Policy.CLAN_FIRST, 2, everyone);
        assertEquals(List.of(2L, 1L), clan.active().stream().map(Entry::userId).toList(), "the clan member gets a place first");
        var unlimited = QueueRules.split(QueueRules.Policy.CLAN_FIRST, 0, everyone);
        assertEquals(3, unlimited.active().size());
        assertTrue(unlimited.waiting().isEmpty());
        assertNull(QueueRules.next(QueueRules.Policy.FIRST_COME, List.of()));
    }

    // ---------- prizes, buy-ins and the pot ----------

    @Test
    void awardsMoveForwardAndNeverBackFromTheirEnd() {
        assertTrue(PrizeRules.canMoveAward(AwardStatus.PENDING, AwardStatus.PAID));
        assertTrue(PrizeRules.canMoveAward(AwardStatus.PAID, AwardStatus.REFUNDED));
        assertTrue(PrizeRules.canMoveAward(AwardStatus.PAID, AwardStatus.REVOKED));
        assertTrue(PrizeRules.canMoveAward(AwardStatus.PENDING, AwardStatus.REVOKED));
        assertFalse(PrizeRules.canMoveAward(AwardStatus.PENDING, AwardStatus.REFUNDED), "nothing to refund before it was paid");
        assertFalse(PrizeRules.canMoveAward(AwardStatus.REVOKED, AwardStatus.PAID));
        assertFalse(PrizeRules.canMoveAward(AwardStatus.REFUNDED, AwardStatus.PAID));
        assertTrue(PrizeRules.nextAward(AwardStatus.REVOKED).isEmpty());
    }

    @Test
    void buyInsAreDueThenPaidThenMaybeRefunded() {
        assertTrue(PrizeRules.canMoveBuyIn(BuyInStatus.NONE, BuyInStatus.DUE));
        assertTrue(PrizeRules.canMoveBuyIn(BuyInStatus.DUE, BuyInStatus.PAID));
        assertTrue(PrizeRules.canMoveBuyIn(BuyInStatus.DUE, BuyInStatus.NONE), "a player who leaves before paying owes nothing");
        assertTrue(PrizeRules.canMoveBuyIn(BuyInStatus.PAID, BuyInStatus.REFUNDED));
        assertFalse(PrizeRules.canMoveBuyIn(BuyInStatus.DUE, BuyInStatus.REFUNDED));
        assertFalse(PrizeRules.canMoveBuyIn(BuyInStatus.REFUNDED, BuyInStatus.PAID));
    }

    @Test
    void thePotCountsPaidBuyInsLessRefunds() {
        var buyIn = new BuyIn(true, PrizeKind.IN_GAME, 5_000_000, true);
        var players = List.of(BuyInStatus.PAID, BuyInStatus.PAID, BuyInStatus.DUE, BuyInStatus.REFUNDED, BuyInStatus.NONE);
        assertEquals(10_000_000, PrizeRules.pot(buyIn, players));
        assertEquals(0, PrizeRules.pot(new BuyIn(false, PrizeKind.IN_GAME, 5_000_000, true), players), "no pot when buy-ins are off");
        assertTrue(PrizeRules.potVisible(buyIn));
        assertFalse(PrizeRules.potVisible(new BuyIn(true, PrizeKind.CLAN_POINTS, 50, false)), "hidden unless the host shows it");
        assertFalse(PrizeRules.potVisible(new BuyIn(false, PrizeKind.CLAN_POINTS, 50, true)));
    }

    @Test
    void prizesAndBuyInsAreValidated() {
        assertTrue(PrizeRules.prizeProblems(new Prize(PrizeKind.IN_GAME, "Zaros godsword", 0, 1)).isEmpty());
        assertTrue(PrizeRules.prizeProblems(new Prize(PrizeKind.CLAN_POINTS, "", 500, 2)).isEmpty());
        assertFalse(PrizeRules.prizeProblems(new Prize(PrizeKind.CLAN_POINTS, "", 0, 1)).isEmpty(), "points need an amount");
        assertFalse(PrizeRules.prizeProblems(new Prize(PrizeKind.IN_GAME, " ", 0, 1)).isEmpty(), "needs a description");
        assertFalse(PrizeRules.prizeProblems(new Prize(PrizeKind.OTHER, "A shout-out", 0, 101)).isEmpty());
        assertTrue(PrizeRules.buyInProblems(new BuyIn(false, null, 0, false)).isEmpty(), "off needs nothing");
        assertFalse(PrizeRules.buyInProblems(new BuyIn(true, PrizeKind.IN_GAME, 0, false)).isEmpty());
        assertTrue(PrizeRules.buyInProblems(new BuyIn(true, PrizeKind.IN_GAME, 1_000_000, true)).isEmpty());
    }
}
