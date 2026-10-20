package com.younglings.bot.starboard;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A server's starboard: messages that collect enough of one reaction (a star, by default) are copied into a showcase channel. Good for
 * a drops-and-achievements channel, where members nominate the best moments by reacting.
 * <p>
 * {@code channelThresholds}: source channels that need a different number of reactions than {@code threshold}. {@code skipHiddenChannels}: never copy
 * out of a channel the @everyone role can't see. {@code onlyChannels}: when not empty, only these channels are copied from. The emoji can be a custom one
 * ({@code <:name:id>}) as well as a unicode one.
 */
public record StarboardConfig(long guildId, boolean enabled, Long channelId, String emoji, int threshold, boolean selfStar,
                              Map<Long, Integer> channelThresholds, boolean skipHiddenChannels, Set<Long> onlyChannels) {
    public static final String DEFAULT_EMOJI = "⭐";
    public static final int DEFAULT_THRESHOLD = 3;
    public static final int MAX_THRESHOLD = 25;
    public static final int MAX_CHANNEL_RULES = 25;

    public StarboardConfig {
        channelThresholds = channelThresholds == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(channelThresholds));
        onlyChannels = onlyChannels == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(onlyChannels));
    }

    public StarboardConfig(long guildId, boolean enabled, Long channelId, String emoji, int threshold, boolean selfStar) {
        this(guildId, enabled, channelId, emoji, threshold, selfStar, Map.of(), false, Set.of());
    }

    public static StarboardConfig defaults(long guildId) {
        return new StarboardConfig(guildId, false, null, DEFAULT_EMOJI, DEFAULT_THRESHOLD, false);
    }

    /** "123:5,456:2" for storing {@code channelThresholds}. */
    public static String writeThresholds(Map<Long, Integer> rules) {
        StringBuilder out = new StringBuilder();
        rules.forEach((channel, n) -> out.append(out.isEmpty() ? "" : ",").append(channel).append(':').append(n));
        return out.toString();
    }

    public static Map<Long, Integer> readThresholds(String stored) {
        Map<Long, Integer> rules = new LinkedHashMap<>();
        if (stored == null || stored.isBlank()) return rules;
        for (String part : stored.split(",")) {
            String[] pair = part.strip().split(":");
            if (pair.length != 2) continue;
            try {
                rules.put(Long.parseLong(pair[0]), Integer.parseInt(pair[1]));
            } catch (NumberFormatException ignored) {
                // skip anything that isn't a rule
            }
        }
        return rules;
    }

    public static String writeIds(Set<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    public static Set<Long> readIds(String stored) {
        Set<Long> ids = new LinkedHashSet<>();
        if (stored == null || stored.isBlank()) return ids;
        for (String part : stored.split(",")) {
            try {
                ids.add(Long.parseLong(part.strip()));
            } catch (NumberFormatException ignored) {
                // skip anything that isn't an id
            }
        }
        return ids;
    }
}
