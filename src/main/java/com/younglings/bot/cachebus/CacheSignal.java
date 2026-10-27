package com.younglings.bot.cachebus;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One cache signal on the {@code bot_cache} channel: the payload is {@code area:guildId}, for example {@code automod:123}. A payload that
 * isn't one (another sender, a damaged message) is ignored, never guessed at.
 */
public record CacheSignal(String area, long guildId) {
    /** The Postgres channel every process listens on. */
    public static final String CHANNEL = "bot_cache";

    private static final Pattern AREA = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    public CacheSignal {
        if (!validArea(area)) throw new IllegalArgumentException("Bad cache area: " + area);
        if (guildId <= 0) throw new IllegalArgumentException("Bad guild id: " + guildId);
    }

    public static boolean validArea(String area) {
        return area != null && AREA.matcher(area).matches();
    }

    /** The signal in {@code payload}, or empty when it isn't one. */
    public static Optional<CacheSignal> parse(String payload) {
        if (payload == null) return Optional.empty();
        int colon = payload.indexOf(':');
        if (colon <= 0 || colon != payload.lastIndexOf(':')) return Optional.empty();
        String area = payload.substring(0, colon);
        String id = payload.substring(colon + 1);
        if (!validArea(area) || id.isEmpty() || id.length() > 20 || !id.chars().allMatch(Character::isDigit)) return Optional.empty();
        try {
            long guildId = Long.parseLong(id);
            return guildId > 0 ? Optional.of(new CacheSignal(area, guildId)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public String payload() {
        return area + ":" + guildId;
    }
}
