package com.younglings.bot.giveaway;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiveawayMessageTest {
    private static Giveaway giveaway(Long winnerRole, boolean reaction) {
        return new Giveaway(1, 2, 3, 4L, "A cape", 5, 3, Instant.parse("2026-11-01T00:00:00Z"), null, Giveaway.Status.OPEN, List.of(), winnerRole, true, reaction);
    }

    @Test
    void aGiveawayThatAllowsReactionsSaysSoAndOneThatDoesNotStaysAsItWas() {
        assertTrue(GiveawayMessage.embed(giveaway(null, true), 0).getDescription().contains("react with " + Giveaway.ENTRY_EMOJI));
        assertFalse(GiveawayMessage.embed(giveaway(null, false), 0).getDescription().contains("react"));
    }

    @Test
    void theWinnerRoleIsShownOnlyWhenThereIsOne() {
        assertTrue(GiveawayMessage.embed(giveaway(99L, false), 0).getDescription().contains("Winners get <@&99>"));
        assertFalse(GiveawayMessage.embed(giveaway(null, false), 0).getDescription().contains("Winners get"));
    }

    @Test
    void upToFiftyWinnersCanBeDrawn() {
        assertEquals(50, Giveaway.MAX_WINNERS);
        List<Long> entrants = java.util.stream.LongStream.rangeClosed(1, 80).boxed().toList();
        assertEquals(50, GiveawayDraw.pick(entrants, 50, java.util.Set.of(), new java.util.Random(1)).size());
    }
}
