package com.runeclans.bot.premium;

import com.runeclans.bot.premium.BillingRules.Action;
import com.runeclans.bot.premium.BillingRules.Decision;
import com.runeclans.bot.premium.BillingRules.SitePlan;
import com.runeclans.bot.premium.BillingRules.Subscription;
import com.runeclans.bot.premium.PremiumGuildRepository.Grant;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The subscription → grant mapping, event order, and the site plan with its grace periods (T-366, docs/BUILDER-V3.md section 10.3). */
class BillingRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-12-01T12:00:00Z");
    private static final long GUILD = 999_000_000_000_000_366L;

    private static Subscription sub(String status, Instant periodEnd, boolean cancelAtEnd, Instant created, String eventId) {
        return new Subscription(GUILD, "cus_test", "sub_test", status, periodEnd, cancelAtEnd, created, eventId);
    }

    private static Grant grant(String source, Instant end) {
        return new Grant(GUILD, "x", "premium", end, null, source, null, NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void statusesMapToTheirGracePeriods() {
        assertEquals(PERIOD_END.plus(Duration.ofDays(3)), BillingRules.grantEnd("active", PERIOD_END, false));
        assertEquals(PERIOD_END.plus(Duration.ofDays(3)), BillingRules.grantEnd("trialing", PERIOD_END, false));
        assertEquals(PERIOD_END, BillingRules.grantEnd("active", PERIOD_END, true), "set to cancel: exactly the period end");
        assertEquals(PERIOD_END.plus(Duration.ofDays(7)), BillingRules.grantEnd("past_due", PERIOD_END, false));
        for (String ending : new String[]{"canceled", "unpaid", "incomplete_expired", "paused"}) assertEquals(PERIOD_END, BillingRules.grantEnd(ending, PERIOD_END, false), ending);
        assertNull(BillingRules.grantEnd("incomplete", PERIOD_END, false), "the first payment hasn't gone through: nothing");
        assertNull(BillingRules.grantEnd("active", null, false));
    }

    @Test
    void onlyStripesStatusesAreAccepted() {
        assertEquals("past_due", BillingRules.cleanStatus(" PAST_DUE "));
        assertNull(BillingRules.cleanStatus("lifetime"));
        assertNull(BillingRules.cleanStatus(null));
    }

    @Test
    void theNewerEventWinsAndTiesAreBrokenByTheEventId() {
        Instant t = NOW;
        assertTrue(BillingRules.newer(sub("active", PERIOD_END, false, t, "evt_b"), null, null));
        assertTrue(BillingRules.newer(sub("active", PERIOD_END, false, t.plusSeconds(1), "evt_a"), t, "evt_b"));
        assertFalse(BillingRules.newer(sub("active", PERIOD_END, false, t.minusSeconds(1), "evt_z"), t, "evt_b"));
        assertTrue(BillingRules.newer(sub("active", PERIOD_END, false, t, "evt_c"), t, "evt_b"));
        assertFalse(BillingRules.newer(sub("active", PERIOD_END, false, t, "evt_a"), t, "evt_b"));
    }

    @Test
    void aManualGrantInForceIsNeverTouched() {
        Decision d = BillingRules.decide(sub("canceled", NOW.minusSeconds(60), false, NOW, "evt_1"), grant("manual", null), NOW);
        assertEquals(Action.KEEP, d.action());
        assertEquals(Action.KEEP, BillingRules.decide(sub("active", PERIOD_END, false, NOW, "evt_1"), grant("trial", PERIOD_END.plusSeconds(1)), NOW).action());
    }

    @Test
    void aManualGrantThatRanOutCanBeReplacedByAPayment() {
        Decision d = BillingRules.decide(sub("active", PERIOD_END, false, NOW, "evt_1"), grant("manual", NOW.minusSeconds(1)), NOW);
        assertEquals(Action.SET, d.action());
        assertEquals(PERIOD_END.plus(Duration.ofDays(3)), d.expiresAt());
    }

    @Test
    void aPaymentGrantsAndACancelEndsAtThePeriodEnd() {
        Decision paid = BillingRules.decide(sub("active", PERIOD_END, false, NOW, "evt_1"), null, NOW);
        assertEquals(Action.SET, paid.action());
        Decision cancelled = BillingRules.decide(sub("canceled", PERIOD_END, false, NOW, "evt_2"), grant("stripe", paid.expiresAt()), NOW);
        assertEquals(Action.SET, cancelled.action());
        assertEquals(PERIOD_END, cancelled.expiresAt());
    }

    @Test
    void anEndedStateCreatesNothingAndAnUnchangedOneWritesNothing() {
        assertEquals(Action.KEEP, BillingRules.decide(sub("canceled", NOW.minusSeconds(60), false, NOW, "evt_1"), null, NOW).action());
        assertEquals(Action.KEEP, BillingRules.decide(sub("incomplete", PERIOD_END, false, NOW, "evt_1"), null, NOW).action());
        Instant end = PERIOD_END.plus(Duration.ofDays(3));
        assertEquals(Action.KEEP, BillingRules.decide(sub("active", PERIOD_END, false, NOW, "evt_1"), grant("stripe", end), NOW).action());
    }

    @Test
    void aCancelAfterThePeriodEndedStillEndsALiveStripeGrant() {
        Instant pastEnd = NOW.minus(Duration.ofDays(1));
        Decision d = BillingRules.decide(sub("canceled", pastEnd, false, NOW, "evt_9"), grant("stripe", pastEnd.plus(Duration.ofDays(3))), NOW);
        assertEquals(Action.SET, d.action());
        assertEquals(pastEnd, d.expiresAt(), "the grant now ends in the past, so it stops at once");
    }

    @Test
    void noGrantOrAnEndedGrantIsFree() {
        assertEquals(SitePlan.FREE, BillingRules.plan(null, null, null, NOW));
        assertEquals(SitePlan.FREE, BillingRules.plan(grant("manual", NOW.minusSeconds(1)), null, null, NOW));
        assertFalse(SitePlan.FREE.premium());
    }

    @Test
    void aManualPlanHasNoGraceWhileTheBotIsThere() {
        SitePlan p = BillingRules.plan(grant("manual", null), null, null, NOW);
        assertEquals(new SitePlan("premium", null, null, "manual"), p);
        assertTrue(p.premium());
    }

    @Test
    void aStripePlanShowsThePaidPeriodAndItsGraceOnceThePeriodIsOver() {
        Instant grantEnd = PERIOD_END.plus(Duration.ofDays(3));
        Subscription s = sub("active", PERIOD_END, false, NOW, "evt_1");
        assertEquals(new SitePlan("premium", PERIOD_END, null, "stripe"), BillingRules.plan(grant("stripe", grantEnd), s, null, NOW));
        Instant afterPeriod = PERIOD_END.plus(Duration.ofDays(1));
        assertEquals(new SitePlan("premium", PERIOD_END, grantEnd, "stripe"), BillingRules.plan(grant("stripe", grantEnd), s, null, afterPeriod));
    }

    @Test
    void pastDueShowsItsGraceAtOnce() {
        Instant grantEnd = PERIOD_END.plus(Duration.ofDays(7));
        SitePlan p = BillingRules.plan(grant("stripe", grantEnd), sub("past_due", PERIOD_END, false, NOW, "evt_1"), null, NOW);
        assertEquals(grantEnd, p.graceUntil());
    }

    @Test
    void theBotLeavingGivesFourteenDaysThenFree() {
        Instant leftAt = NOW.minus(Duration.ofDays(5));
        SitePlan p = BillingRules.plan(grant("manual", null), null, leftAt, NOW);
        assertEquals("premium", p.tier());
        assertEquals(leftAt.plus(Duration.ofDays(14)), p.graceUntil());

        assertEquals(SitePlan.FREE, BillingRules.plan(grant("manual", null), null, NOW.minus(Duration.ofDays(14)), NOW));
        assertEquals(SitePlan.FREE, BillingRules.plan(grant("manual", null), null, NOW.minus(Duration.ofDays(20)), NOW));
    }

    @Test
    void theGraceIsNeverLaterThanTheGrantItself() {
        Instant leftAt = NOW.minus(Duration.ofDays(1));
        Instant grantEnd = NOW.plus(Duration.ofDays(2));
        assertEquals(grantEnd, BillingRules.plan(grant("manual", grantEnd), null, leftAt, NOW).graceUntil());
    }

    @Test
    void onlyRenewingSubscriptionsOfLongGoneServersAreReconciled() {
        Instant longAgo = NOW.minus(Duration.ofDays(15));
        assertTrue(BillingRules.shouldReconcile(sub("active", PERIOD_END, false, NOW, "e"), longAgo, NOW));
        assertTrue(BillingRules.shouldReconcile(sub("past_due", PERIOD_END, false, NOW, "e"), longAgo, NOW));
        assertFalse(BillingRules.shouldReconcile(sub("active", PERIOD_END, true, NOW, "e"), longAgo, NOW), "already set to cancel");
        assertFalse(BillingRules.shouldReconcile(sub("canceled", PERIOD_END, false, NOW, "e"), longAgo, NOW));
        assertFalse(BillingRules.shouldReconcile(sub("active", PERIOD_END, false, NOW, "e"), NOW.minus(Duration.ofDays(13)), NOW));
        assertFalse(BillingRules.shouldReconcile(sub("active", PERIOD_END, false, NOW, "e"), null, NOW), "the bot is there");
    }

    @Test
    void theSiteFeatureKeysAreRegisteredWithTheirLimits() {
        for (String key : new String[]{PremiumFeatures.SITE_EDITOR, PremiumFeatures.SITE_STYLE, PremiumFeatures.SITE_USER_THEMES, PremiumFeatures.SITE_ADMIN_FULL,
                PremiumFeatures.SITE_LIMITS, PremiumFeatures.SITE_NO_BADGE}) {
            assertTrue(PremiumFeatures.allows("premium", key), key);
            assertFalse(PremiumFeatures.allows(null, key), key);
        }
        assertEquals(12L, PremiumFeatures.limit(null, PremiumFeatures.SITE_PAGES).orElseThrow());
        assertEquals(24L, PremiumFeatures.limit("premium", PremiumFeatures.SITE_PAGES).orElseThrow());
        assertEquals(40L, PremiumFeatures.limit(null, PremiumFeatures.SITE_WIDGETS_PER_PAGE).orElseThrow());
        assertEquals(80L, PremiumFeatures.limit("premium", PremiumFeatures.SITE_WIDGETS_PER_PAGE).orElseThrow());
        assertEquals(3L, PremiumFeatures.limit(null, PremiumFeatures.SITE_VERSIONS).orElseThrow());
        assertEquals(30L, PremiumFeatures.limit("premium", PremiumFeatures.SITE_VERSIONS).orElseThrow());
    }
}
