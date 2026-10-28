package com.runeclans.bot.clansite;

import java.util.Locale;
import java.util.Optional;

/**
 * One piece of a clan website's own content: a text block (a small Markdown subset, no HTML), an image, or a list of links, placed on one
 * of the site's pages ({@code home}, {@code about}, {@code custom1}..{@code custom3}) at a position. {@code body} is JSON the website reads;
 * the admin routes check it before it is stored.
 */
public record ClanSiteBlock(long guildId, String page, int position, Kind kind, String body) {

    public enum Kind {
        TEXT, IMAGE, LINKS;

        public static Optional<Kind> parse(String raw) {
            if (raw == null) return Optional.empty();
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
