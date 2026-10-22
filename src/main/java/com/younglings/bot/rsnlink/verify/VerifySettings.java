package com.younglings.bot.rsnlink.verify;

import java.time.Duration;
import java.util.function.Function;

/**
 * Settings for the "plugin knew it first" check (T-170), from the environment. Off unless {@code LINK_VERIFY_ENABLED=true}.
 *
 * @param enabled              {@code LINK_VERIFY_ENABLED}, default false
 * @param minMatches           {@code LINK_VERIFY_MIN_MATCHES}: matched predictions needed, default 2 (one lucky guess is never enough)
 * @param minGainXp            {@code LINK_VERIFY_MIN_GAIN_XP}: a prediction counts only if it is at least this many whole XP above what was
 *                             public when it was checked, default 100, so small easy-to-guess gains don't count
 * @param checksPerLinkPerDay  {@code LINK_VERIFY_CHECKS_PER_LINK_DAY}: fresh public-data checks per link per UTC day, default 24
 * @param checksPerMinute      {@code LINK_VERIFY_CHECKS_PER_MINUTE}: fresh checks (two or three requests each) per minute for the whole service, default 20
 * @param userAgent            sent to Jagex with every read so they can tell who is calling
 */
public record VerifySettings(boolean enabled, int minMatches, int minGainXp, int checksPerLinkPerDay, int checksPerMinute, String userAgent) {

    /** A pending prediction older than this expires (the player never logged out, or never ranked). */
    public static final Duration CLAIM_LIFETIME = Duration.ofHours(72);
    /** A report that couldn't be checked within this long is refused: it was never shown to be a prediction. */
    public static final Duration UNCHECKED_LIFETIME = Duration.ofHours(1);
    /** Most XP values one link may report per hour (a plugin batching every couple of minutes sends far fewer). */
    public static final int CLAIMS_PER_LINK_HOUR = 120;
    /** Settled claims and idle watches are deleted after this. */
    public static final Duration KEPT = Duration.ofDays(30);
    /** How long one public read may be reused (both sides of a dispute read the same name). Never reused for a claim made after it. */
    public static final Duration CACHE = Duration.ofSeconds(60);
    /** How often the ironman table is probed again for a player who wasn't on it. */
    public static final Duration IRONMAN_REPROBE = Duration.ofDays(7);

    public static VerifySettings off() {
        return new VerifySettings(false, 2, 100, 24, 20, "RS Link verification");
    }

    /** @param env looks a variable up, null when unset */
    public static VerifySettings fromEnv(Function<String, String> env, String userAgent) {
        boolean on = "true".equalsIgnoreCase(trim(env.apply("LINK_VERIFY_ENABLED")));
        return new VerifySettings(on, number(env, "LINK_VERIFY_MIN_MATCHES", 2, 1, 20), number(env, "LINK_VERIFY_MIN_GAIN_XP", 100, 1, 1_000_000),
                number(env, "LINK_VERIFY_CHECKS_PER_LINK_DAY", 24, 2, 500), number(env, "LINK_VERIFY_CHECKS_PER_MINUTE", 20, 1, 600), userAgent);
    }

    private static int number(Function<String, String> env, String name, int fallback, int min, int max) {
        String raw = trim(env.apply(name));
        if (raw == null) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
