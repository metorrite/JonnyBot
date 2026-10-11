package com.younglings.bot.giveaway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiveawayDrawTest {
    @Test
    void thereAreAsManyWinnersAsAskedForAndNeverTheSamePersonTwice() {
        List<Long> winners = GiveawayDraw.pick(List.of(1L, 2L, 3L, 4L, 5L), 3, Set.of(), new Random(7));

        assertEquals(3, winners.size());
        assertEquals(3, winners.stream().distinct().count());
    }

    @Test
    void fewerEntrantsThanWinnersMeansEveryoneWins() {
        assertEquals(2, GiveawayDraw.pick(List.of(1L, 2L), 5, Set.of(), new Random(1)).size());
    }

    @Test
    void noEntrantsMeansNoWinners() {
        assertTrue(GiveawayDraw.pick(List.of(), 3, Set.of(), new Random(1)).isEmpty());
    }

    @Test
    void aRerollNeverDrawsAPreviousWinner() {
        for (int seed = 0; seed < 50; seed++) {
            List<Long> winners = GiveawayDraw.pick(List.of(1L, 2L, 3L, 4L), 2, Set.of(1L, 2L), new Random(seed));

            assertFalse(winners.contains(1L) || winners.contains(2L));
        }
    }

    @Test
    void someoneWhoEnteredTwiceStillHasOneChance() {
        assertEquals(1, GiveawayDraw.pick(List.of(9L, 9L, 9L), 3, Set.of(), new Random(1)).size());
    }

    @Test
    void theSameSeedGivesTheSameDraw() {
        assertEquals(GiveawayDraw.pick(List.of(1L, 2L, 3L, 4L, 5L, 6L), 2, Set.of(), new Random(42)), GiveawayDraw.pick(List.of(1L, 2L, 3L, 4L, 5L, 6L), 2, Set.of(), new Random(42)));
    }

    @Test
    void winnersAreNamedAsMentions() {
        assertEquals("<@1>", GiveawayDraw.mentions(List.of(1L)));
        assertEquals("<@1> and <@2>", GiveawayDraw.mentions(List.of(1L, 2L)));
        assertEquals("<@1>, <@2> and <@3>", GiveawayDraw.mentions(List.of(1L, 2L, 3L)));
        assertEquals("", GiveawayDraw.mentions(List.of()));
    }

    @Test
    void theButtonShowsHowManyHaveEntered() {
        assertEquals("Enter", GiveawayDraw.enterLabel(0));
        assertEquals("Enter (12)", GiveawayDraw.enterLabel(12));
        assertEquals("gw:5", GiveawayDraw.buttonId(5));
    }
}
