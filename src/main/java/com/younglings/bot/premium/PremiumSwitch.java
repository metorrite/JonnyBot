package com.younglings.bot.premium;

import com.younglings.bot.config.BotConfig;

/**
 * The one premium switch for clan websites (T-422, Drake's "everything is free for now"). With {@code PREMIUM_ENABLED} unset, every
 * {@code site.*} feature counts as unlocked for every server: the Premium page, widget, version and picture limits, the full editor, style
 * and no-badge. Nothing is granted or stored; setting {@code PREMIUM_ENABLED=1} brings the plans back exactly as they were. The website has
 * the same switch under the same name. Features outside the website (faster polling, the bot's server profile) keep following the plans.
 */
public final class PremiumSwitch {
    private static final String SITE_PREFIX = "site.";
    private static volatile Boolean forced;

    private PremiumSwitch() {}

    /** Whether Premium exists for clan websites: {@code PREMIUM_ENABLED} is 1 or true. */
    public static boolean on() {
        Boolean f = forced;
        return f != null ? f : BotConfig.getInstance().getPremiumEnabled();
    }

    /** The tier a check uses: the server's own, or Premium for a website feature while the switch is off. */
    static String tierFor(String tier, String featureKey) {
        if (featureKey != null && featureKey.startsWith(SITE_PREFIX) && !on()) return PremiumFeatures.TIER_PREMIUM;
        return tier;
    }

    /** Tests only: pin the switch ({@code null} goes back to the environment). */
    public static void forceForTests(Boolean value) {
        forced = value;
    }
}
