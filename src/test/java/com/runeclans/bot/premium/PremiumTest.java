package com.runeclans.bot.premium;

import com.runeclans.bot.premium.PremiumGuildRepository.Grant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The feature registry, the one check, and granting, revoking and expiring plans (no payments, prices or billing anywhere). */
class PremiumTest {
    private PremiumGuildRepository repository;
    private Premium premium;

    @BeforeEach
    void setUp() {
        repository = mock(PremiumGuildRepository.class);
        premium = new Premium(repository);
        PremiumSwitch.forceForTests(true);
    }

    @AfterEach
    void switchBack() {
        PremiumSwitch.forceForTests(null);
    }

    @Test
    void premiumSwitchedOffUnlocksEveryWebsiteFeatureAndNothingElse() {
        when(repository.activeGrant(1L)).thenReturn(null);
        PremiumSwitch.forceForTests(false);

        for (PremiumFeatures.Feature f : PremiumFeatures.all()) {
            if (!f.key().startsWith("site.")) continue;
            assertTrue(premium.allows(1L, f.key()), f.key());
            if (f.isLimit()) assertEquals(Optional.of(f.premiumValue()), premium.limit(1L, f.key()), f.key());
        }
        assertFalse(premium.allows(1L, PremiumFeatures.FAST_POLLING), "outside the website the plans still decide");
        assertFalse(premium.allows(1L, PremiumFeatures.BRANDING_PROFILE));
        assertNull(premium.tierOf(1L), "nothing is granted or stored");

        PremiumSwitch.forceForTests(true);
        assertFalse(premium.allows(1L, PremiumFeatures.SITE_EDITOR), "switched on, a server without a plan is free again");
        assertEquals(Optional.of(12L), premium.limit(1L, PremiumFeatures.SITE_PAGES));
    }

    private static Grant grant(long guildId, String tier, Instant end) {
        return new Grant(guildId, "x", tier, end, null, "manual", 1L, Instant.now());
    }

    @Test
    void todaysTwoGatesAreOnlyForThePremiumTier() {
        assertTrue(PremiumFeatures.allows("premium", PremiumFeatures.FAST_POLLING));
        assertTrue(PremiumFeatures.allows("premium", PremiumFeatures.BRANDING_PROFILE));
        assertFalse(PremiumFeatures.allows(null, PremiumFeatures.FAST_POLLING), "no plan, no feature");
        assertFalse(PremiumFeatures.allows("plus", PremiumFeatures.BRANDING_PROFILE), "a tier the registry doesn't name unlocks nothing");
        assertFalse(PremiumFeatures.allows("premium", "no.such.feature"));
    }

    @Test
    void aLimitIsTheFreeValueUnlessTheTierUnlocksIt() {
        var limit = new PremiumFeatures.Feature("x.limit", "x", "x", Set.of("premium"), 10L, 40L);

        assertTrue(limit.isLimit());
        assertEquals(Optional.empty(), PremiumFeatures.limit("premium", PremiumFeatures.FAST_POLLING), "an on/off feature has no number");
        assertEquals(Optional.empty(), PremiumFeatures.limit("premium", "nope"));
    }

    @Test
    void aServerWithoutAnActiveGrantHasNothing() {
        when(repository.activeGrant(1L)).thenReturn(null);

        assertNull(premium.tierOf(1L));
        assertFalse(premium.allows(1L, PremiumFeatures.FAST_POLLING));
    }

    @Test
    void anActivePremiumGrantUnlocksTheFeatures() {
        when(repository.activeGrant(1L)).thenReturn(grant(1L, "premium", null));

        assertTrue(premium.allows(1L, PremiumFeatures.FAST_POLLING));
        assertTrue(premium.allows(1L, PremiumFeatures.BRANDING_PROFILE));
    }

    @Test
    void aGrantIsInForceUntilItsEnd() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");

        assertTrue(PremiumFeatures.inForce(null, now));
        assertTrue(PremiumFeatures.inForce(now.plusSeconds(1), now));
        assertFalse(PremiumFeatures.inForce(now, now));
        assertFalse(PremiumFeatures.inForce(now.minusSeconds(1), now));
        assertTrue(PremiumFeatures.endingSoon(now.plus(6, ChronoUnit.DAYS), now, 7));
        assertFalse(PremiumFeatures.endingSoon(now.plus(8, ChronoUnit.DAYS), now, 7));
        assertFalse(PremiumFeatures.endingSoon(null, now, 7));
    }

    @Test
    void theFastPollingServersAreTheOnesWithAPlanInForce() {
        Instant now = Instant.now();
        when(repository.grants()).thenReturn(List.of(grant(1L, "premium", null), grant(2L, "premium", now.minusSeconds(60)), grant(3L, "plus", null), grant(4L, "premium", now.plusSeconds(3600))));

        assertEquals(List.of(1L, 4L), premium.guildsAllowing(PremiumFeatures.FAST_POLLING));
    }

    @Test
    void grantingIsLoggedAsGrantThenChange() {
        when(repository.grant(1L)).thenReturn(null, grant(1L, "premium", null));

        assertFalse(premium.grant(1L, "x", "premium", null, "n", "manual", 9L));
        assertTrue(premium.grant(1L, "x", "plus", null, "n", "manual", 9L));

        verify(repository).log(1L, "GRANT", "premium", null, 9L, "n");
        verify(repository).log(1L, "CHANGE", "plus", null, 9L, "n");
    }

    @Test
    void revokingIsLoggedAndAMissingGrantIsNot() {
        when(repository.grant(1L)).thenReturn(grant(1L, "premium", null));
        when(repository.delete(1L)).thenReturn(true);

        assertTrue(premium.revoke(1L, 9L));
        assertFalse(premium.revoke(2L, 9L));

        verify(repository).log(eq(1L), eq("REVOKE"), eq("premium"), any(), eq(9L), any());
        verify(repository, never()).log(eq(2L), anyString(), any(), any(), any(), any());
    }

    @Test
    void theDailySweepRemovesEndedGrantsAndLogsEachAsExpiredWithoutAUser() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        Grant ended = grant(1L, "premium", now.minusSeconds(1));
        when(repository.expired(now)).thenReturn(List.of(ended));
        when(repository.delete(1L)).thenReturn(true);

        assertEquals(1, premium.sweep(now));

        verify(repository).log(1L, "EXPIRE", "premium", ended.expiresAt(), null, null);
    }

    @Test
    void aFailingSweepIsSwallowedSoItCanRunAgainTomorrow() {
        when(repository.expired(any())).thenThrow(new RuntimeException("db down"));

        assertEquals(0, premium.sweep(Instant.now()));
        verify(repository, never()).delete(anyLong());
    }
}
