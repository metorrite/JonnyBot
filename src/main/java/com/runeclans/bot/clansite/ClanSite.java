package com.runeclans.bot.clansite;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * A clan's own website from the website builder (docs/DOMAINS-AND-SITE-BUILDER.md part B, website repository): served at
 * {@code <clan profile slug>.<platform domain>}. It shows only while it is switched on, published and not taken down, and only for a
 * server allowed into the builder's beta. {@code theme} and {@code pages} are JSON the website reads (colours, logo, banner, font; which
 * pages show and in what order); the bot stores them as given after the admin routes have checked them.
 */
public record ClanSite(long guildId, boolean enabled, boolean published, Template template, String theme, String pages, Long updatedBy,
                       Instant updatedAt, Instant publishedAt, String takedownReason) {

    /** The three looks a clan can pick from. */
    public enum Template {
        CLASSIC("Classic"), HALL("Hall"), MINIMAL("Minimal");

        private final String label;

        Template(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Optional<Template> parse(String raw) {
            if (raw == null) return Optional.empty();
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** A server that has never touched the builder: off, unpublished, the Classic look, nothing customised. */
    public static ClanSite empty(long guildId) {
        return new ClanSite(guildId, false, false, Template.CLASSIC, "{}", "[]", null, null, null, null);
    }

    /** Whether the public may see this site (the beta check is separate: see {@link ClanSiteService}). */
    public boolean live() {
        return enabled && published && (takedownReason == null || takedownReason.isBlank());
    }
}
