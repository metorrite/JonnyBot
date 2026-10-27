package com.younglings.bot.beta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@code BETA_SEED_GUILDS} is read (no database). Ids are made up at runtime. */
class BetaSeedTest {
    private static final String A = "6".repeat(18);
    private static final String B = "7".repeat(19);

    @Test
    void unsetOrBlankSeedsNothing() {
        for (String raw : new String[]{null, "", "   ", " , ,"}) {
            BetaSeed.Parsed parsed = BetaSeed.parse(raw);
            assertTrue(parsed.entries().isEmpty(), String.valueOf(raw));
            assertTrue(parsed.skipped().isEmpty(), String.valueOf(raw));
        }
    }

    @Test
    void idsWithAndWithoutLabels() {
        BetaSeed.Parsed parsed = BetaSeed.parse(" " + A + " : Clan A: the first ," + B + ",");

        assertEquals(List.of(new BetaSeed.Entry(Long.parseLong(A), "Clan A: the first"), new BetaSeed.Entry(Long.parseLong(B), "")), parsed.entries());
        assertTrue(parsed.skipped().isEmpty());
    }

    @Test
    void entriesThatAreNotIdsAreSkippedByPosition() {
        String tooShort = "1".repeat(14);
        String tooLarge = "9".repeat(20); // twenty digits, but more than a long holds
        BetaSeed.Parsed parsed = BetaSeed.parse("not-an-id," + A + ":ok," + tooShort + "," + tooLarge + ",:label only,-" + A);

        assertEquals(List.of(new BetaSeed.Entry(Long.parseLong(A), "ok")), parsed.entries());
        assertEquals(List.of(1, 3, 4, 5, 6), parsed.skipped());
    }

    @Test
    void aRepeatedIdKeepsItsFirstLabel() {
        BetaSeed.Parsed parsed = BetaSeed.parse(A + ":first," + A + ":second");

        assertEquals(List.of(new BetaSeed.Entry(Long.parseLong(A), "first")), parsed.entries());
    }

    @Test
    void aLongLabelIsCut() {
        BetaSeed.Parsed parsed = BetaSeed.parse(A + ":" + "x".repeat(300));

        assertEquals(100, parsed.entries().get(0).label().length());
    }
}
