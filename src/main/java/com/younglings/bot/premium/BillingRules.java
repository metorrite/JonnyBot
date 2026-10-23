package com.younglings.bot.premium;

import com.younglings.bot.premium.PremiumGuildRepository.Grant;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * How a paid subscription turns into a plan, and what plan a clan website has (docs/BUILDER-V3.md in the runeclans repo, section 10). Pure: no
 * database, no Discord, no Stripe, so every rule has a table test. The website owns the Stripe keys and forwards what Stripe said; the bot only
 * ever sees the subscription's state.
 * <ul>
 *   <li>Active or trialing: the plan runs to the end of the paid period plus {@link #ACTIVE_GRACE} (a renewal a little late doesn't flicker
 *   the site), or exactly to the period end when it is set to cancel then.</li>
 *   <li>Past due (Stripe is retrying the card): the period end plus {@link #PAST_DUE_GRACE}.</li>
 *   <li>Canceled, unpaid, ended or paused: the plan ends at the period end. No refund logic of our own.</li>
 *   <li>Incomplete (the first payment hasn't gone through) or anything unknown: no change.</li>
 *   <li>The bot left the server: the site keeps its plan for {@link #BOT_LEFT_GRACE}, then drops to free.</li>
 * </ul>
 */
public final class BillingRules {
    public static final Duration ACTIVE_GRACE = Duration.ofDays(3);
    public static final Duration PAST_DUE_GRACE = Duration.ofDays(7);
    public static final Duration BOT_LEFT_GRACE = Duration.ofDays(14);
    public static final String SOURCE_STRIPE = "stripe";
    public static final String TIER_FREE = "free";

    private static final Set<String> RUNNING = Set.of("active", "trialing");
    private static final Set<String> ENDING = Set.of("canceled", "unpaid", "incomplete_expired", "paused");
    /** Every subscription status Stripe has; anything else is refused at the door. */
    public static final Set<String> STATUSES = Set.of("active", "trialing", "past_due", "canceled", "unpaid", "incomplete", "incomplete_expired", "paused");

    private BillingRules() {}

    /** A subscription as the website forwarded it. {@code created} is when Stripe made the event: newer wins, whatever order events arrive in. */
    public record Subscription(long guildId, String customerId, String subscriptionId, String status, Instant currentPeriodEnd,
                               boolean cancelAtPeriodEnd, Instant created, String eventId) {}

    /** The plan a clan website has right now. {@code tier} is {@link #TIER_FREE} when it has none; the other fields are then null. */
    public record SitePlan(String tier, Instant until, Instant graceUntil, String source) {
        public static final SitePlan FREE = new SitePlan(TIER_FREE, null, null, null);

        public boolean premium() {
            return !TIER_FREE.equals(tier);
        }
    }

    /** A status as Stripe spells it, or null if it isn't one. */
    public static String cleanStatus(String raw) {
        if (raw == null) return null;
        String status = raw.strip().toLowerCase(Locale.ROOT);
        return STATUSES.contains(status) ? status : null;
    }

    /** When the plan should end for this subscription state; null means the state grants nothing and changes nothing (incomplete). */
    public static Instant grantEnd(String status, Instant periodEnd, boolean cancelAtPeriodEnd) {
        if (periodEnd == null || status == null) return null;
        if (RUNNING.contains(status)) return cancelAtPeriodEnd ? periodEnd : periodEnd.plus(ACTIVE_GRACE);
        if (status.equals("past_due")) return periodEnd.plus(PAST_DUE_GRACE);
        if (ENDING.contains(status)) return periodEnd;
        return null;
    }

    /**
     * Whether {@code incoming} is newer than the event the stored state came from. Ties on Stripe's time are broken by the event id, so the final
     * state is the same whatever order the events arrive in.
     */
    public static boolean newer(Subscription incoming, Instant storedCreated, String storedEventId) {
        if (storedCreated == null) return true;
        int byTime = incoming.created().compareTo(storedCreated);
        if (byTime != 0) return byTime > 0;
        return storedEventId == null || incoming.eventId().compareTo(storedEventId) > 0;
    }

    /** What to do to the server's grant. {@code KEEP}: leave it. {@code SET}: grant or change it to end at the given time. */
    public enum Action { KEEP, SET }

    public record Decision(Action action, Instant expiresAt, String why) {
        static Decision keep(String why) {
            return new Decision(Action.KEEP, null, why);
        }
    }

    /**
     * What a subscription state does to the server's grant ({@code current}: its grant in force or not, null if none). A grant from anywhere but
     * Stripe that is still in force (the owner's manual grant) always wins and is never touched. An ending state with nothing to end creates nothing.
     */
    public static Decision decide(Subscription sub, Grant current, Instant now) {
        if (current != null && !SOURCE_STRIPE.equals(current.source()) && current.inForce(now)) return Decision.keep("a " + current.source() + " grant wins");
        Instant end = grantEnd(sub.status(), sub.currentPeriodEnd(), sub.cancelAtPeriodEnd());
        if (end == null) return Decision.keep("status " + sub.status() + " changes nothing");
        boolean stripeGrant = current != null && SOURCE_STRIPE.equals(current.source());
        if (!end.isAfter(now) && !(stripeGrant && current.inForce(now))) return Decision.keep("ended already, nothing to end");
        if (stripeGrant && PremiumFeatures.TIER_PREMIUM.equals(current.tier()) && end.equals(current.expiresAt())) return Decision.keep("unchanged");
        return new Decision(Action.SET, end, "status " + sub.status());
    }

    /**
     * The plan a clan website has. {@code grant}: the server's grant (in force or not); {@code sub}: its stored subscription, if any;
     * {@code leftAt}: when the bot left the server, null while it is there.
     * <ul>
     *   <li>{@code until}: the end of the paid period for a Stripe plan, the grant's end otherwise (null = no end).</li>
     *   <li>{@code graceUntil}: set only while a grace period runs (the bot left, or the paid period is over and Stripe's grace is counting down):
     *   the moment the site drops to free unless something changes.</li>
     * </ul>
     */
    public static SitePlan plan(Grant grant, Subscription sub, Instant leftAt, Instant now) {
        if (grant == null || !grant.inForce(now)) return SitePlan.FREE;
        Instant botDeadline = leftAt == null ? null : leftAt.plus(BOT_LEFT_GRACE);
        if (botDeadline != null && !botDeadline.isAfter(now)) return SitePlan.FREE;

        boolean stripe = SOURCE_STRIPE.equals(grant.source()) && sub != null && sub.currentPeriodEnd() != null;
        Instant until = stripe ? sub.currentPeriodEnd() : grant.expiresAt();
        Instant grace = null;
        if (stripe && grant.expiresAt() != null && grant.expiresAt().isAfter(until) && (!now.isBefore(until) || "past_due".equals(sub.status()))) grace = grant.expiresAt();
        if (botDeadline != null) grace = grace == null || botDeadline.isBefore(grace) ? botDeadline : grace;
        if (grace != null && grant.expiresAt() != null && grant.expiresAt().isBefore(grace)) grace = grant.expiresAt();
        return new SitePlan(grant.tier(), until, grace, grant.source());
    }

    /** Whether a server should have its subscription cancelled at the period end: the bot has been gone for the whole grace, and it still renews. */
    public static boolean shouldReconcile(Subscription sub, Instant leftAt, Instant now) {
        if (sub == null || leftAt == null || sub.cancelAtPeriodEnd()) return false;
        if (!RUNNING.contains(sub.status()) && !"past_due".equals(sub.status())) return false;
        return !leftAt.plus(BOT_LEFT_GRACE).isAfter(now);
    }
}
