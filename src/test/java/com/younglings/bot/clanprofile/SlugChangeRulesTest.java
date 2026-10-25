package com.younglings.bot.clanprofile;

import com.younglings.bot.clanprofile.SlugChangeRules.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The address change clock (D4): a one-hour window that edits don't restart, then a 30-day cooldown. */
class SlugChangeRulesTest {
    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void aRequestIsDueOnlyOnceItsHourIsOver() {
        State s = SlugChangeRules.requested(State.NONE, "new-name", T0);
        assertFalse(SlugChangeRules.due(s, T0.plus(Duration.ofMinutes(59))));
        assertTrue(SlugChangeRules.due(s, T0.plus(Duration.ofMinutes(60))));
        assertEquals(T0.plus(Duration.ofHours(1)), SlugChangeRules.status(s, T0).appliesAt());
    }

    @Test
    void changingTheRequestedAddressDuringTheHourKeepsTheClock() {
        State first = SlugChangeRules.requested(State.NONE, "one", T0);
        State second = SlugChangeRules.requested(first, "two", T0.plus(Duration.ofMinutes(40)));
        assertEquals("two", second.pendingSlug());
        assertEquals(T0, second.requestedAt());
    }

    @Test
    void aRequestWhoseHourIsOverStartsAFreshWindowIfAskedAgain() {
        State first = SlugChangeRules.requested(State.NONE, "one", T0);
        State again = SlugChangeRules.requested(first, "two", T0.plus(Duration.ofHours(2)));
        assertEquals(T0.plus(Duration.ofHours(2)), again.requestedAt());
    }

    @Test
    void applyingStartsThirtyDaysOfCooldownAndCancellingKeepsIt() {
        State applied = SlugChangeRules.applied(T0);
        assertTrue(SlugChangeRules.coolingDown(applied, T0.plus(Duration.ofDays(29))));
        assertFalse(SlugChangeRules.coolingDown(applied, T0.plus(Duration.ofDays(30))));
        assertNull(SlugChangeRules.status(applied, T0.plus(Duration.ofDays(31))).cooldownUntil());
        State pending = SlugChangeRules.requested(applied, "x", T0.plus(Duration.ofDays(31)));
        State cancelled = SlugChangeRules.cancelled(pending);
        assertFalse(cancelled.hasPending());
        assertEquals(T0, cancelled.lastChangedAt());
    }

    @Test
    void aSiteThatNeverChangedItsAddressIsNotCoolingDown() {
        assertFalse(SlugChangeRules.coolingDown(State.NONE, T0));
        assertFalse(SlugChangeRules.due(State.NONE, T0));
    }
}
