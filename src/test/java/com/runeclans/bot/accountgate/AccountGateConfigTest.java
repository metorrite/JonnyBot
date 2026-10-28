package com.runeclans.bot.accountgate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountGateConfigTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private final AccountGateConfig week = new AccountGateConfig(1, true, 7, AccountGateConfig.Action.ALERT, null);

    @Test
    void anAccountYoungerThanTheLimitIsTooNew() {
        assertTrue(week.tooNew(NOW.minus(Duration.ofDays(6)), NOW));
        assertTrue(week.tooNew(NOW.minus(Duration.ofMinutes(5)), NOW));
    }

    @Test
    void anAccountAtOrPastTheLimitIsFine() {
        assertFalse(week.tooNew(NOW.minus(Duration.ofDays(7)), NOW));
        assertFalse(week.tooNew(NOW.minus(Duration.ofDays(400)), NOW));
    }

    @Test
    void theEligibleTimeIsTheLimitAfterCreation() {
        Instant created = NOW.minus(Duration.ofDays(2));

        assertEquals(created.plus(Duration.ofDays(7)), week.eligibleAt(created));
    }

    @Test
    void ageIsWrittenInPlainWords() {
        assertEquals("0 minutes", AccountGateConfig.age(NOW, NOW));
        assertEquals("1 minute", AccountGateConfig.age(NOW.minus(Duration.ofMinutes(1)), NOW));
        assertEquals("1 hour", AccountGateConfig.age(NOW.minus(Duration.ofMinutes(75)), NOW));
        assertEquals("5 hours", AccountGateConfig.age(NOW.minus(Duration.ofHours(5)), NOW));
        assertEquals("1 day", AccountGateConfig.age(NOW.minus(Duration.ofHours(30)), NOW));
        assertEquals("3 days", AccountGateConfig.age(NOW.minus(Duration.ofDays(3)), NOW));
    }

    @Test
    void defaultsAreOffAndOnlyAlert() {
        AccountGateConfig d = AccountGateConfig.defaults(9);

        assertFalse(d.enabled());
        assertEquals(AccountGateConfig.Action.ALERT, d.action());
        assertEquals(7, d.minAgeDays());
    }
}
