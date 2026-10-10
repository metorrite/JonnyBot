package com.younglings.bot.runescape;

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
}
