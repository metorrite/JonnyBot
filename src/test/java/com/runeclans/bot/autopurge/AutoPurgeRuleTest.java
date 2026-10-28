package com.runeclans.bot.autopurge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoPurgeRuleTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private final AutoPurgeRule day = new AutoPurgeRule(1, 2, 24 * 60, true);

    @Test
    void ageMustBeBetweenAnHourAndJustUnderTwoWeeks() {
        assertFalse(AutoPurgeRule.validAge(59));
        assertTrue(AutoPurgeRule.validAge(60));
        assertTrue(AutoPurgeRule.validAge(13 * 24 * 60));
        assertFalse(AutoPurgeRule.validAge(14 * 24 * 60));
    }

    @Test
    void messagesOlderThanTheAgeAreDue() {
        assertTrue(day.shouldDelete(NOW.minus(Duration.ofHours(25)), false, NOW));
        assertFalse(day.shouldDelete(NOW.minus(Duration.ofHours(23)), false, NOW));
        assertFalse(day.shouldDelete(NOW.minus(Duration.ofHours(24)), false, NOW), "exactly the age is not yet older");
    }

    @Test
    void pinnedMessagesAreKeptUnlessTheRuleSaysOtherwise() {
        Instant old = NOW.minus(Duration.ofDays(2));

        assertFalse(day.shouldDelete(old, true, NOW));
        assertTrue(new AutoPurgeRule(1, 2, 24 * 60, false).shouldDelete(old, true, NOW));
    }

    @Test
    void messagesTooOldForDiscordToBulkDeleteAreLeftAlone() {
        assertFalse(day.shouldDelete(NOW.minus(Duration.ofDays(14)), false, NOW));
        assertTrue(day.shouldDelete(NOW.minus(Duration.ofDays(13)), false, NOW));
    }

    @Test
    void theCutoffIsTheAgeBeforeNow() {
        assertEquals(NOW.minus(Duration.ofDays(1)), day.cutoff(NOW));
    }

    @Test
    void agesAreDescribedInPlainWords() {
        assertEquals("1 day", AutoPurgeRule.describe(1440));
        assertEquals("3 days", AutoPurgeRule.describe(3 * 1440));
        assertEquals("6 hours", AutoPurgeRule.describe(360));
        assertEquals("1 hour", AutoPurgeRule.describe(60));
        assertEquals("90 minutes", AutoPurgeRule.describe(90));
    }
}
