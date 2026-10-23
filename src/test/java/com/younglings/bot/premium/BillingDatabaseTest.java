package com.younglings.bot.premium;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.premium.BillingRules.SitePlan;
import com.younglings.bot.premium.BillingRules.Subscription;
import com.younglings.bot.premium.PremiumGuildRepository.Grant;
import com.younglings.bot.retention.GuildRetentionRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The billing link over real SQL (T-366): replays and out-of-order events end in the same grant, a manual grant survives a Stripe cancel, and a
 * site drops to free 14 days after the bot leaves. Skipped without a local database.
 */
class BillingDatabaseTest {
    private static final long GUILD = 999_000_000_000_000_366L;
    private static final long OTHER = 999_000_000_000_000_367L;
    private static ConnectionSupplier supplier;

    private PremiumGuildRepository grants;
    private BillingRepository repository;
    private GuildRetentionRepository retention;
    private Billing billing;
    private Instant now;
    private Instant periodEnd;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        PremiumDatabaseInitializer initializer = new PremiumDatabaseInitializer(supplier);
        grants = new PremiumGuildRepository(supplier, initializer);
        repository = new BillingRepository(supplier, initializer);
        retention = new GuildRetentionRepository(supplier);
        billing = new Billing(repository, grants, new Premium(grants), retention, mock(BotConfig.class));
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        periodEnd = now.plus(Duration.ofDays(30));
        cleanUp();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"premium_guild", "premium_grant_log", "premium_subscription", "billing_event", "guild_departure"}) {
            try (Connection c = supplier.getConnection();
                 PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                s.setLong(1, GUILD);
                s.setLong(2, OTHER);
                s.executeUpdate();
            }
        }
    }

    private Subscription event(long guild, String id, String status, boolean cancelAtEnd, int secondsAfter) {
        return new Subscription(guild, "cus_test", "sub_test", status, periodEnd, cancelAtEnd, now.plusSeconds(secondsAfter), id + "_" + guild);
    }

    private int logLines(long guild) {
        return grants.history(guild, 100).size();
    }

    private void leftDaysAgo(long guild, int days) throws Exception {
        try (Connection c = supplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.guild_departure (guild_id, left_at) VALUES (?, ?) ON CONFLICT (guild_id) DO UPDATE SET left_at = EXCLUDED.left_at")) {
            s.setLong(1, guild);
            s.setTimestamp(2, Timestamp.from(now.minus(Duration.ofDays(days))));
            s.executeUpdate();
        }
    }

    @Test
    void aPaymentGrantsPremiumUntilThePeriodEndPlusThreeDays() {
        assertEquals(Billing.Outcome.APPLIED, billing.apply(event(GUILD, "evt_paid", "active", false, 0), "koalas", now).outcome());

        Grant grant = grants.grant(GUILD);
        assertEquals("premium", grant.tier());
        assertEquals("stripe", grant.source());
        assertEquals("koalas", grant.label());
        assertNull(grant.addedBy(), "nobody typed it in");
        assertEquals(periodEnd.plus(Duration.ofDays(3)), grant.expiresAt());
        assertEquals(new SitePlan("premium", periodEnd, null, "stripe"), billing.plan(GUILD, true, now));
    }

    @Test
    void aReplayedEventDoesNothingTheSecondTime() {
        Subscription paid = event(GUILD, "evt_paid", "active", false, 0);
        billing.apply(paid, "x", now);
        int lines = logLines(GUILD);

        assertEquals(Billing.Outcome.DUPLICATE, billing.apply(paid, "x", now).outcome());
        assertEquals(lines, logLines(GUILD), "no second history line");
    }

    @Test
    void outOfOrderAndReplayedEventsAlwaysEndInTheSameGrant() throws Exception {
        List<Subscription> events = List.of(event(GUILD, "evt_1", "active", false, 0), event(GUILD, "evt_2", "past_due", false, 10),
                event(GUILD, "evt_3", "active", true, 20), event(GUILD, "evt_4", "canceled", false, 30));
        Instant expected = null;
        String expectedStatus = null;
        for (List<Subscription> order : permutations(events)) {
            cleanUp();
            for (Subscription e : order) billing.apply(e, "x", now);
            for (Subscription e : order) billing.apply(e, "x", now); // and every one replayed
            Instant end = grants.grant(GUILD).expiresAt();
            String status = repository.subscription(GUILD).orElseThrow().status();
            if (expected == null) {
                expected = end;
                expectedStatus = status;
            }
            assertEquals(expected, end, "order " + order.stream().map(Subscription::eventId).toList());
            assertEquals(expectedStatus, status);
        }
        assertEquals(periodEnd, expected, "the newest event (canceled) decides: the plan ends at the period end");
        assertEquals("canceled", expectedStatus);
    }

    @Test
    void anOlderEventIsRecordedButChangesNothing() {
        billing.apply(event(GUILD, "evt_new", "canceled", false, 50), "x", now);
        Instant end = grants.grant(GUILD) == null ? null : grants.grant(GUILD).expiresAt();

        assertEquals(Billing.Outcome.STALE, billing.apply(event(GUILD, "evt_old", "active", false, 0), "x", now).outcome());
        assertTrue(repository.seen("evt_old_" + GUILD));
        assertEquals("canceled", repository.subscription(GUILD).orElseThrow().status());
        assertEquals(end, grants.grant(GUILD) == null ? null : grants.grant(GUILD).expiresAt());
    }

    @Test
    void aManualGrantSurvivesAStripeCancel() {
        new Premium(grants).grant(GUILD, "friends", "premium", null, "on the house", "manual", 7L);
        billing.apply(event(GUILD, "evt_paid", "active", false, 0), "x", now);
        billing.apply(event(GUILD, "evt_cancel", "canceled", false, 10), "x", now);

        Grant grant = grants.grant(GUILD);
        assertEquals("manual", grant.source());
        assertNull(grant.expiresAt());
        assertEquals("friends", grant.label());
        assertEquals("canceled", repository.subscription(GUILD).orElseThrow().status(), "the subscription is still recorded");
        assertEquals(new SitePlan("premium", null, null, "manual"), billing.plan(GUILD, true, now));
    }

    @Test
    void thePlanDropsToFreeFourteenDaysAfterTheBotLeaves() throws Exception {
        billing.apply(event(GUILD, "evt_paid", "active", false, 0), "x", now);

        leftDaysAgo(GUILD, 5);
        SitePlan grace = billing.plan(GUILD, false, now);
        assertEquals("premium", grace.tier());
        assertEquals(retention.leftAt(GUILD).plus(Duration.ofDays(14)), grace.graceUntil());
        assertEquals(new SitePlan("premium", periodEnd, null, "stripe"), billing.plan(GUILD, true, now), "the bot is back: no grace");

        leftDaysAgo(GUILD, 14);
        assertEquals(SitePlan.FREE, billing.plan(GUILD, false, now));
        assertEquals(List.of(GUILD), billing.toReconcile(now).stream().map(Subscription::guildId).filter(g -> g == GUILD).toList(),
                "its renewing subscription goes to the website to be cancelled");
    }

    @Test
    void noDepartureOnRecordCountsAsPresent() {
        billing.apply(event(OTHER, "evt_paid", "active", false, 0), "x", now);
        assertEquals("premium", billing.plan(OTHER, false, now).tier());
        assertTrue(billing.toReconcile(now).stream().noneMatch(s -> s.guildId() == OTHER));
    }

    @Test
    void theStaticLookupNeverFails() {
        billing.apply(event(GUILD, "evt_paid", "active", false, 0), "x", now);
        assertEquals("premium", Billing.planJson(GUILD, true).getString("tier"));
        assertEquals("free", Billing.planJson(OTHER, true).getString("tier"));
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) return List.of(List.of());
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> p = new ArrayList<>();
                p.add(head);
                p.addAll(tail);
                out.add(p);
            }
        }
        return out;
    }
}
