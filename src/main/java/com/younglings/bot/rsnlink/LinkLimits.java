package com.younglings.bot.rsnlink;

import java.time.Duration;

/**
 * Every number from LINK-SYSTEM.md sections 7 and 8 in one place, so they can be tuned without hunting. In-memory limits reset on a
 * restart, which is fine for one instance; anything that must survive one (lockouts, blocks, DM caps) is counted in the database.
 */
public final class LinkLimits {
    private LinkLimits() {}

    // ---------- lifetimes (section 7) ----------
    public static final Duration PAIRING_LIFETIME = Duration.ofMinutes(10);
    public static final int POLL_INTERVAL_SECONDS = 5;
    public static final int SLOW_DOWN_STEP_SECONDS = 5;
    public static final int MAX_POLL_INTERVAL_SECONDS = 60;
    public static final Duration ENDED_PAIRING_KEPT = Duration.ofHours(24);
    public static final Duration ENDED_LINK_KEPT = Duration.ofDays(30);
    public static final Duration ABUSE_EVENT_KEPT = Duration.ofDays(30);
    public static final Duration TOKEN_UNUSED_REVOKE = Duration.ofDays(365);
    public static final Duration DM_OPT_OUT = Duration.ofDays(90);
    /** Section 11: a tracked character that hasn't reported a name for this long no longer holds it. */
    public static final Duration RECYCLE_AFTER = Duration.ofDays(30);

    // ---------- counts ----------
    public static final int MAX_ACTIVE_LINKS_PER_DISCORD = 20;
    public static final int MAX_ACTIVE_TOKENS_PER_LINK = 5;
    public static final int WRONG_CODES_PER_PAIRING = 5;

    // ---------- rate limits (section 8): {max, window} ----------
    public static final Window WEB_CODE_PER_IP_MINUTE = new Window(10, Duration.ofMinutes(1));
    public static final Window WEB_CODE_PER_IP_HOUR = new Window(30, Duration.ofHours(1));
    public static final Window WRONG_CODES_PER_DISCORD = new Window(10, Duration.ofHours(1));
    public static final Window START_PER_IP = new Window(10, Duration.ofHours(1));
    public static final Window START_PER_TRACKING = new Window(5, Duration.ofHours(1));
    public static final Window START_PER_RSN = new Window(5, Duration.ofHours(1));
    public static final Window DM_PER_TARGET_SHORT = new Window(1, Duration.ofMinutes(10));
    public static final Window DM_PER_TARGET_DAY = new Window(3, Duration.ofDays(1));
    public static final Window DM_PER_IP = new Window(5, Duration.ofHours(1));
    public static final Window DM_PER_TRACKING = new Window(3, Duration.ofHours(1));
    public static final Window DM_TOTAL = new Window(300, Duration.ofHours(1));
    public static final Window TOKEN_ROUTES_PER_TOKEN = new Window(60, Duration.ofMinutes(1));
    public static final Window ALIAS_PER_TOKEN = new Window(30, Duration.ofMinutes(1));
    /** Web routes after sign-in (my links, unlink, delete): generous, only against floods. */
    public static final Window WEB_ACCOUNT_PER_DISCORD = new Window(60, Duration.ofMinutes(1));

    // ---------- repeat offenders ----------
    public static final int LOCKOUTS_BEFORE_BLOCK = 3;
    public static final Duration LOCKOUT_COUNT_WINDOW = Duration.ofHours(24);
    public static final Duration AUTO_BLOCK = Duration.ofHours(24);
    public static final Duration DISCORD_CODE_COOLDOWN = Duration.ofHours(1);

    /** At most {@code max} actions in any {@code window}. */
    public record Window(int max, Duration window) {}
}
