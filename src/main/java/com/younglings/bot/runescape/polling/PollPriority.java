package com.younglings.bot.runescape.polling;

/**
 * Who is waiting on a poll, which decides where it stands in the one queue every RuneMetrics request goes through.
 * Declaration order is priority order: a request never waits behind one that comes later in this list, though a
 * lower tier is not starved either, because the queue only reorders what is due and the rate bucket is shared.
 * <p>
 * Adding a tier (a paid or "prioritized" clan, say) is one new constant in the right place plus a recurring
 * {@link PollJob} that submits at it; nothing else in the coordinator knows how many tiers there are.
 */
public enum PollPriority {
    /** A person pressed a button and is waiting for the answer ("Update now", a lookup, an admin refresh of one player). */
    INTERACTIVE,
    /** Reserved: clans on a faster polling plan. Unused until that plan exists, but holding the slot means adding it needs no queue changes. */
    PREMIUM_CLAN,
    /** Everyone in a registered clan: the reason the tracking feeds exist, so the first of the recurring tiers. */
    CLAN,
    /** Anyone else who linked an account: their profile only needs to stay roughly current. */
    LINKED;

    /** Whether this tier is served before {@code other}. */
    public boolean beats(PollPriority other) {
        return ordinal() < other.ordinal();
    }
}
