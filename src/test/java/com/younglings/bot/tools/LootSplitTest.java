package com.younglings.bot.tools;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LootSplitTest {
    @Test
    void amountsAreReadTheWayPlayersWriteThem() {
        assertEquals(Optional.of(250_000L), LootSplit.parseAmount("250k"));
        assertEquals(Optional.of(1_500_000L), LootSplit.parseAmount("1.5m"));
        assertEquals(Optional.of(2_000_000_000L), LootSplit.parseAmount("2B"));
        assertEquals(Optional.of(1_250_000L), LootSplit.parseAmount("1,250,000"));
        assertEquals(Optional.of(1_250_000L), LootSplit.parseAmount(" 1 250 000 "));
        assertEquals(Optional.of(12_500_000L), LootSplit.parseAmount("12,500k"));
    }

    @Test
    void nonsenseAndOutOfRangeAmountsAreRefused() {
        for (String bad : new String[]{"", "abc", "-5m", "0", "0.4", "1.2.3m", "5x", "m", "2000b", null}) {
            assertEquals(Optional.empty(), LootSplit.parseAmount(bad), String.valueOf(bad));
        }
    }

    @Test
    void anEvenSplitLeavesNothingOver() {
        LootSplit.Result r = LootSplit.split(1_000_000, 4, 0);

        assertEquals(250_000, r.share());
        assertEquals(0, r.remainder());
        assertEquals(0, r.fee());
    }

    @Test
    void coinsThatDoNotDivideAreReportedAsARemainder() {
        LootSplit.Result r = LootSplit.split(100, 3, 0);

        assertEquals(33, r.share());
        assertEquals(1, r.remainder());
        assertEquals(100, r.share() * r.people() + r.remainder());
    }

    @Test
    void theFeeComesOffTheTopAndRoundsDown() {
        LootSplit.Result r = LootSplit.split(1_000_001, 2, 10);

        assertEquals(100_000, r.fee());
        assertEquals(900_001, r.pot());
        assertEquals(450_000, r.share());
        assertEquals(1, r.remainder());
        assertEquals(r.total(), r.fee() + r.share() * r.people() + r.remainder());
    }

    @Test
    void aFractionalFeeWorks() {
        assertEquals(25_000, LootSplit.split(1_000_000, 5, 2.5).fee());
    }

    @Test
    void badInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LootSplit.split(100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LootSplit.split(100, LootSplit.MAX_PEOPLE + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> LootSplit.split(100, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> LootSplit.split(100, 2, 101));
        assertThrows(IllegalArgumentException.class, () -> LootSplit.split(100, 2, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LootSplit.split(-1, 2, 0));
    }

    @Test
    void largeAmountsGetAShortFormBesideThem() {
        assertEquals("1,250,000 (1.25m)", LootSplit.format(1_250_000));
        assertEquals("2,000,000,000 (2b)", LootSplit.format(2_000_000_000L));
        assertEquals("15,000 (15k)", LootSplit.format(15_000));
        assertEquals("9,999", LootSplit.format(9_999));
    }
}
