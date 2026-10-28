package com.runeclans.bot.runescape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RosterPollSchedulerTest {

    @Test
    void aLinkedPlayerInAClanIsPolledWithTheClanNotAgain() {
        List<String> others = RosterPollScheduler.otherLinkedPlayers(
                List.of("Jonny Young", "Zezima", "Lynx Titan"), List.of("jonny young"));

        assertEquals(List.of("Zezima", "Lynx Titan"), others);
    }

    @Test
    void everyoneLinkedIsInTheSecondTierWhenNoClanIsRegistered() {
        List<String> accounts = List.of("A", "B");

        assertEquals(accounts, RosterPollScheduler.otherLinkedPlayers(accounts, List.of()));
    }

    @Test
    void clanPlayersWhoNeverLinkedAreNotAddedToTheSecondTier() {
        assertEquals(List.of(), RosterPollScheduler.otherLinkedPlayers(List.of(), List.of("Someone Unlinked")));
    }

    @Test
    void aPremiumPlayerIsLeftOutOfTheOrdinaryClanTierSoNobodyIsPolledTwice() {
        List<String> standard = RosterPollScheduler.standardClanPlayers(List.of("A", "B", "C"), List.of("b"));

        assertEquals(List.of("A", "C"), standard);
    }

    @Test
    void withNoPremiumServersEveryClanPlayerStaysInTheOrdinaryTier() {
        assertEquals(List.of("A", "B"), RosterPollScheduler.standardClanPlayers(List.of("A", "B"), List.of()));
    }
}
