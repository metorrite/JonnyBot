package com.younglings.bot.rsnlink;

import java.util.Locale;

/**
 * How strongly a link is known to belong to the person who made it (LINK-SYSTEM.md section 12, extended by the 2026-10-20 research).
 * Stored as text in {@code link.verification}, so adding a level needs no migration. v1 makes every link {@link #IMPLICIT}; the other
 * levels exist so the {@link LinkVerifier} hook (parked ticket T-122) can raise a link without a schema or API change.
 */
public enum Verification {
    /** Taken on trust: the plugin said so. Every link in v1. */
    IMPLICIT("implicit", 0),
    /** Public Jagex data later matched something only the player's own client could have known first (e.g. exact XP before Hiscores). */
    PUBLIC_DATA_CORROBORATED("public_data_corroborated", 1),
    /** Jagex itself vouched for the session (does not exist yet; asked of Jagex in T-119). */
    JAGEX_SIGNED("jagex_signed", 2);

    private final String key;
    private final int strength;

    Verification(String key, int strength) {
        this.key = key;
        this.strength = strength;
    }

    /** The value stored and sent to plugins, e.g. {@code "implicit"}. */
    public String key() {
        return key;
    }

    public int strength() {
        return strength;
    }

    public boolean strongerThan(Verification other) {
        return strength > other.strength;
    }

    /** The level for a stored value; anything unknown counts as {@link #IMPLICIT}, never stronger. */
    public static Verification of(String stored) {
        if (stored != null) {
            for (Verification v : values()) if (v.key.equals(stored.toLowerCase(Locale.ROOT))) return v;
        }
        return IMPLICIT;
    }
}
