package com.younglings.bot.premium;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a plan unlocks: the one list of gated features. Pure (no database, no Discord) so it can be tested and so a plan change is a data change
 * here, not a hunt through the code. A feature is either on or off for a tier ({@link #allows}), or has a number that depends on the tier
 * ({@link #limit}). Every feature today is switched on only for the {@code premium} tier, and every limit's free value is what it already is, so
 * nothing behaves differently from before this registry existed. No prices or billing live here (docs/PREMIUM-PLAN.md section 6).
 */
public final class PremiumFeatures {
    public static final String TIER_PREMIUM = "premium";

    /** Members of a premium clan are refreshed more often and take request slots first. */
    public static final String FAST_POLLING = "polling.fast";
    /** The bot's server avatar, banner and bio. */
    public static final String BRANDING_PROFILE = "branding.profile";

    // Clan websites, builder v3 (docs/BUILDER-V3.md in the runeclans repo, section 10). The website reads the plan from the bot and never decides on its own.
    /** The premium widgets and layout features of the site editor (custom spans, resize, breakpoint overrides). */
    public static final String SITE_EDITOR = "site.editor";
    /** The site's own colours, fonts, corners, density, glow and buttons. */
    public static final String SITE_STYLE = "site.style";
    /** Visitors may pick a theme for the site. */
    public static final String SITE_USER_THEMES = "site.userThemes";
    /** Every page of the site's own admin dashboard (free sites get Overview, Status and Plan). */
    public static final String SITE_ADMIN_FULL = "site.admin.full";
    /** How many saved versions of the site are kept. */
    public static final String SITE_VERSIONS = "site.versions";
    /** The bigger page and widget limits ({@link #SITE_PAGES}, {@link #SITE_WIDGETS} hold the numbers). */
    public static final String SITE_LIMITS = "site.limits";
    /** Pages a site may have. */
    public static final String SITE_PAGES = "site.limits.pages";
    /** Widgets a page may have. */
    public static final String SITE_WIDGETS = "site.limits.widgets";
    /** The "Made with RuneClans" footer badge may be turned off. */
    public static final String SITE_NO_BADGE = "site.noBadge";

    /** {@code unlockedBy}: the tiers that get the feature (or the premium value of a limit). {@code freeValue}/{@code premiumValue}: null for a plain on/off feature. */
    public record Feature(String key, String title, String description, Set<String> unlockedBy, Long freeValue, Long premiumValue) {
        public boolean isLimit() {
            return freeValue != null;
        }
    }

    private static final List<Feature> ALL = List.of(
            new Feature(FAST_POLLING, "Faster player updates", "Your clan's players are refreshed every hour instead of every three hours, ahead of other clans in the queue.",
                    Set.of(TIER_PREMIUM), null, null),
            new Feature(BRANDING_PROFILE, "Server profile", "Give the bot its own avatar, banner and bio in your server.", Set.of(TIER_PREMIUM), null, null),
            new Feature(SITE_EDITOR, "Full site editor", "Every widget, custom column widths, resizing and separate layouts for phones and tablets.", Set.of(TIER_PREMIUM), null, null),
            new Feature(SITE_STYLE, "Site style", "Your own colours, fonts, corners and spacing on your clan website.", Set.of(TIER_PREMIUM), null, null),
            new Feature(SITE_USER_THEMES, "Visitor themes", "Let visitors pick the theme they read your website in.", Set.of(TIER_PREMIUM), null, null),
            new Feature(SITE_ADMIN_FULL, "Full site admin", "Every page of your website's admin dashboard.", Set.of(TIER_PREMIUM), null, null),
            new Feature(SITE_VERSIONS, "Saved versions", "Up to 30 earlier versions of your website kept to go back to (free: 3).", Set.of(TIER_PREMIUM), 3L, 30L),
            new Feature(SITE_LIMITS, "Bigger website", "More pages, and more widgets on each page.", Set.of(TIER_PREMIUM), null, null),
            new Feature(SITE_PAGES, "Website pages", "Up to 24 pages on your website (free: 12).", Set.of(TIER_PREMIUM), 12L, 24L),
            new Feature(SITE_WIDGETS, "Widgets a page", "Up to 80 widgets on each page (free: 40).", Set.of(TIER_PREMIUM), 40L, 80L),
            new Feature(SITE_NO_BADGE, "No footer badge", "Turn off the \"Made with RuneClans\" badge at the bottom of your website.", Set.of(TIER_PREMIUM), null, null));

    private static final Map<String, Feature> BY_KEY = ALL.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Feature::key, f -> f));

    private PremiumFeatures() {}

    public static List<Feature> all() {
        return ALL;
    }

    public static Optional<Feature> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /** Whether a server on {@code tier} (null = no plan) has the feature. An unknown feature is never allowed. */
    public static boolean allows(String tier, String featureKey) {
        Feature feature = BY_KEY.get(featureKey);
        return feature != null && tier != null && feature.unlockedBy().contains(tier);
    }

    /** The number for a limit on {@code tier}: the premium value for a tier that unlocks it, the free value otherwise. Empty if it isn't a limit. */
    public static Optional<Long> limit(String tier, String featureKey) {
        Feature feature = BY_KEY.get(featureKey);
        if (feature == null || !feature.isLimit()) return Optional.empty();
        return Optional.of(tier != null && feature.unlockedBy().contains(tier) && feature.premiumValue() != null ? feature.premiumValue() : feature.freeValue());
    }

    /** Whether a grant that ends at {@code expiresAt} (null = never) is still in force at {@code now}. */
    public static boolean inForce(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    /** Whether a grant ends within {@code days} days of {@code now} without having ended already. */
    public static boolean endingSoon(Instant expiresAt, Instant now, int days) {
        return expiresAt != null && expiresAt.isAfter(now) && expiresAt.isBefore(now.plusSeconds(days * 86_400L));
    }

    /** A tier name as the owner may type it: short, lower case, letters digits and dashes. Null if it isn't one. */
    public static String cleanTier(String typed) {
        if (typed == null) return null;
        String tier = typed.strip().toLowerCase(java.util.Locale.ROOT);
        return tier.matches("[a-z0-9][a-z0-9-]{0,19}") ? tier : null;
    }
}
