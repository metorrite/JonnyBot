package com.runeclans.bot.internal;

import com.runeclans.bot.premium.BillingRules.Subscription;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the billing route accepts from the website (T-366): every field it needs, Stripe's statuses, ISO or Unix-second times. */
class BillingApiTest {
    private static final String GUILD = Long.toString(999_000_000_000_000_366L);

    private static DataObject valid() {
        return DataObject.empty().put("eventId", "evt_test_1").put("guildId", GUILD).put("customerId", "cus_test").put("subscriptionId", "sub_test")
                .put("status", "active").put("currentPeriodEnd", 1_796_000_000L).put("cancelAtPeriodEnd", false).put("created", "2026-11-01T00:00:00Z");
    }

    @Test
    void aCompleteEventIsRead() {
        Subscription sub = BillingApi.parse(valid());
        assertEquals(999_000_000_000_000_366L, sub.guildId());
        assertEquals("active", sub.status());
        assertEquals(Instant.ofEpochSecond(1_796_000_000L), sub.currentPeriodEnd());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), sub.created());
        assertFalse(sub.cancelAtPeriodEnd());
        assertEquals("evt_test_1", sub.eventId());
    }

    @Test
    void timesMayBeUnixSecondsAsText() {
        assertEquals(Instant.ofEpochSecond(1_796_000_000L), BillingApi.parse(valid().put("currentPeriodEnd", "1796000000")).currentPeriodEnd());
        assertTrue(BillingApi.parse(valid().put("cancelAtPeriodEnd", true)).cancelAtPeriodEnd());
        assertNull(BillingApi.parse(valid().putNull("currentPeriodEnd")).currentPeriodEnd(), "an incomplete subscription may have no period yet");
    }

    @Test
    void theAskerComesFromTheQueryString() {
        assertEquals(999_000_000_000_000_366L, BillingApi.userIdFrom("x=1&userId=" + GUILD));
        assertNull(BillingApi.userIdFrom("userId=abc"));
        assertNull(BillingApi.userIdFrom(null));
    }

    @Test
    void anythingMissingOrMalformedIsRefused() {
        for (String key : new String[]{"eventId", "guildId", "customerId", "subscriptionId", "status", "created"}) {
            DataObject body = valid();
            body.remove(key);
            assertNull(BillingApi.parse(body), key);
        }
        assertNull(BillingApi.parse(valid().put("status", "lifetime")), "not a Stripe status");
        assertNull(BillingApi.parse(valid().put("guildId", "12")), "not a server id");
        assertNull(BillingApi.parse(valid().put("eventId", "evt with spaces")));
        assertNull(BillingApi.parse(valid().put("currentPeriodEnd", "soon")));
        assertNull(BillingApi.parse(valid().put("created", "yesterday")));
    }
}
