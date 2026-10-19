package com.younglings.bot.customtag;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One tag: a named reply staff wrote once for members to bring up with {@code /tag use name}. Every tag lives under the single {@code /tag} command, so the
 * server's list of slash commands never grows. {@code delivery}: CHANNEL answers where it was used, PRIVATE only to the member who asked, DM sends it to
 * their direct messages. {@code allowedRoles}: when not empty, only members with one of these roles can use it. {@code cooldownSeconds}: how long a member
 * waits before using this tag again (0 for none; staff with Manage Server are never held up). A tag never pings anybody.
 */
public record CustomTag(Long id, long guildId, String name, String content, boolean embed, String embedTitle, Integer embedColor, Delivery delivery,
                        Set<Long> allowedRoles, int cooldownSeconds, boolean enabled, long uses) {
    public enum Delivery { CHANNEL, PRIVATE, DM }

    public static final int MAX_PER_GUILD = 100;
    public static final int MAX_NAME = 32;
    public static final int MAX_CONTENT = 1800;
    public static final int MAX_TITLE = 100;
    public static final int MAX_ROLES = 25;
    public static final int MAX_COOLDOWN = 24 * 3600;
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    public CustomTag {
        allowedRoles = allowedRoles == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(allowedRoles));
        delivery = delivery == null ? Delivery.CHANNEL : delivery;
        embedTitle = embedTitle == null ? "" : embedTitle;
    }

    /** The name as stored: trimmed, lower case. */
    public static String normalise(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /** "#rrggbb" for a stored colour, or an empty string. */
    public static String hex(Integer colour) {
        return colour == null ? "" : String.format("#%06x", colour & 0xFFFFFF);
    }

    /** The colour for "#rrggbb" (or "rrggbb"); null when blank or not a colour. */
    public static Integer parseColour(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(s, 16);
    }
}
