package com.runeclans.bot.highlight;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A server's Highlights: members choose words, and get a direct message when one is said in a channel they can see. Off until a server
 * switches it on. {@code excludedChannels}: never watched (a thread counts as its channel). {@code allowedRoles}: when not empty, only
 * members with one of these roles use it. {@code cooldownSeconds}: after a word has sent a member a message, it stays quiet for them this long.
 */
public record HighlightConfig(long guildId, boolean enabled, int maxKeywords, int cooldownSeconds, Set<Long> excludedChannels, Set<Long> allowedRoles) {
    public static final int DEFAULT_MAX_KEYWORDS = 10;
    public static final int MAX_KEYWORDS_LIMIT = 25;
    public static final int DEFAULT_COOLDOWN = 300;
    public static final int MIN_COOLDOWN = 10;
    public static final int MAX_COOLDOWN = 24 * 3600;
    public static final int MAX_LIST = 50;
    public static final int MAX_WORD = 50;
    public static final int MIN_WORD = 2;
    public static final int MAX_MEMBER_IGNORES = 25;

    public HighlightConfig {
        excludedChannels = excludedChannels == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(excludedChannels));
        allowedRoles = allowedRoles == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(allowedRoles));
    }

    public static HighlightConfig defaults(long guildId) {
        return new HighlightConfig(guildId, false, DEFAULT_MAX_KEYWORDS, DEFAULT_COOLDOWN, Set.of(), Set.of());
    }
}
