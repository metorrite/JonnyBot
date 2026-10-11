package com.younglings.bot.points;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsRulesTest {
    @Test
    void anAwardNeedsAnAmountAndAReason() {
        assertNull(PointsRules.awardProblem(50, "Hosted the Friday raid"));
        assertNull(PointsRules.awardProblem(-50, "Refund for a cancelled prize"));
        assertNotNull(PointsRules.awardProblem(0, "Nothing happened"));
        assertNotNull(PointsRules.awardProblem(10, null));
        assertNotNull(PointsRules.awardProblem(10, "  ab "));
    }

    @Test
    void oneEntryCannotMoveTooMuchOrHaveARamblingReason() {
        assertNull(PointsRules.awardProblem(PointsRules.MAX_PER_ENTRY, "A huge prize pool payout"));
        assertNotNull(PointsRules.awardProblem(PointsRules.MAX_PER_ENTRY + 1, "A mistyped zero"));
        assertNotNull(PointsRules.awardProblem(-PointsRules.MAX_PER_ENTRY - 1, "A mistyped zero"));
        assertNotNull(PointsRules.awardProblem(5, "x".repeat(PointsRules.MAX_REASON + 1)));
    }

    @Test
    void aBalanceCannotGoBelowZero() {
        assertFalse(PointsRules.overdraws(100, -100));
        assertTrue(PointsRules.overdraws(100, -101));
        assertFalse(PointsRules.overdraws(0, 5));
    }

    @Test
    void amountsAreShownWithTheServersName() {
        assertEquals("1,250 points", PointsRules.format(1250, "points"));
        assertEquals("1 ticket", PointsRules.format(1, "tickets"));
        assertEquals("2 tickets", PointsRules.format(2, "tickets"));
        assertEquals("5 points", PointsRules.format(5, " "));
    }

    @Test
    void theNameForPointsMustBeAShortWord() {
        assertNull(PointsRules.unitProblem("tickets"));
        assertNull(PointsRules.unitProblem("clan coins"));
        assertNotNull(PointsRules.unitProblem(""));
        assertNotNull(PointsRules.unitProblem("1337"));
        assertNotNull(PointsRules.unitProblem("<@everyone>"));
        assertNotNull(PointsRules.unitProblem("a".repeat(PointsRules.MAX_UNIT + 1)));
        assertTrue(PointsRules.settingsProblems("tickets").isEmpty());
    }
}
