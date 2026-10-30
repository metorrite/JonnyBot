package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RosterPollSchedulerTest {
    private static final HubPollingList.Groups NO_HUB = HubPollingList.Groups.EMPTY;

    @Test
    void aLinkedPlayerInAClanIsPolledWithTheClanNotAgain() {
        RosterPollScheduler.Tiers tiers = RosterPollScheduler.tiers(List.of("jonny young"), List.of(), List.of("Jonny Young", "Zezima", "Lynx Titan"), NO_HUB);

        assertEquals(List.of("jonny young"), tiers.clan());
        assertEquals(List.of("Zezima", "Lynx Titan"), tiers.linked());
    }

    @Test
    void everyoneLinkedIsInTheLinkedTierWhenNoClanIsRegistered() {
        assertEquals(List.of("A", "B"), RosterPollScheduler.tiers(List.of(), List.of(), List.of("A", "B"), NO_HUB).linked());
    }

    @Test
    void clanPlayersWhoNeverLinkedAreNotAddedToTheLinkedTier() {
        assertEquals(List.of(), RosterPollScheduler.tiers(List.of("Someone Unlinked"), List.of(), List.of(), NO_HUB).linked());
    }

    @Test
    void aPremiumPlayerIsLeftOutOfTheOrdinaryClanTierSoNobodyIsPolledTwice() {
        assertEquals(List.of("A", "C"), RosterPollScheduler.tiers(List.of("A", "B", "C"), List.of("b"), List.of(), NO_HUB).clan());
    }

    @Test
    void withNoPremiumServersEveryClanPlayerStaysInTheOrdinaryTier() {
        assertEquals(List.of("A", "B"), RosterPollScheduler.tiers(List.of("A", "B"), List.of(), List.of(), NO_HUB).clan());
    }

    /** T-736 acceptance: a character in a clan and a community is polled once per cycle, at CLAN. */
    @Test
    void aClanMemberWhoAlsoJoinedACommunityIsOnlyInTheClanTier() {
        HubPollingList.Groups hub = new HubPollingList.Groups(List.of(), List.of("Iron Wolf"), List.of());
        RosterPollScheduler.Tiers tiers = RosterPollScheduler.tiers(List.of("iron wolf"), List.of(), List.of("Iron Wolf"), hub);

        assertEquals(List.of("iron wolf"), tiers.clan());
        assertEquals(List.of(), tiers.community());
        assertEquals(List.of(), tiers.linked());
    }

    /** T-736 acceptance: a RuneLink-only character (no /rsn) is polled at LINKED. */
    @Test
    void aRuneLinkOnlyPlayerIsInTheLinkedTier() {
        HubPollingList.Groups hub = new HubPollingList.Groups(List.of(), List.of(), List.of("Link Only"));

        assertEquals(List.of("Link Only"), RosterPollScheduler.tiers(List.of(), List.of(), List.of(), hub).linked());
    }

    @Test
    void theHubsClanGroupJoinsTheClanTierEvenWithoutAServerForThatClan() {
        HubPollingList.Groups hub = new HubPollingList.Groups(List.of("Site Clanner", "Premium P"), List.of("Pvm Friend", "site clanner"), List.of("PVM FRIEND", "Loner"));
        RosterPollScheduler.Tiers tiers = RosterPollScheduler.tiers(List.of("Bot Clanner"), List.of("premium p"), List.of("Loner", "Rsn Only"), hub);

        assertEquals(List.of("Bot Clanner", "Site Clanner"), tiers.clan());
        assertEquals(List.of("Pvm Friend"), tiers.community());
        assertEquals(List.of("Loner", "Rsn Only"), tiers.linked());
    }

    @Test
    void aNameTwiceInOneListIsQueuedOnce() {
        HubPollingList.Groups hub = new HubPollingList.Groups(List.of(), List.of("A", "a"), List.of("B", "b"));
        RosterPollScheduler.Tiers tiers = RosterPollScheduler.tiers(List.of(), List.of(), List.of("b"), hub);

        assertEquals(List.of("A"), tiers.community());
        assertEquals(List.of("b"), tiers.linked());
    }
}
