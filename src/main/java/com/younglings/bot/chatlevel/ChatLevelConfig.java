package com.younglings.bot.chatlevel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A server's chat levels: members earn a little XP for chatting and rise through levels, separate from the clan points ledger (nothing here ever writes it).
 * Off until switched on. A message earns between {@code minXp} and {@code maxXp} (times any multiplier), at most once per {@code cooldownSeconds} per member.
 * {@code ignoredChannels} (a thread counts as its channel) and {@code ignoredRoles} earn nothing. Multipliers are per channel or per role (the highest role
 * multiplier a member has applies; 0 earns nothing). {@code announce}: say so when someone levels up, in {@code announceChannelId} (the channel they were
 * chatting in when null) using {@code announceMessage}. {@code removeOldRoles}: a level role replaces the one before it instead of adding to it.
 */
public record ChatLevelConfig(long guildId, boolean enabled, int minXp, int maxXp, int cooldownSeconds, boolean announce, Long announceChannelId, String announceMessage,
                              boolean removeOldRoles, Set<Long> ignoredChannels, Set<Long> ignoredRoles, Map<Long, Double> channelMultipliers, Map<Long, Double> roleMultipliers) {
    public static final int DEFAULT_MIN_XP = 15;
    public static final int DEFAULT_MAX_XP = 25;
    public static final int MAX_XP = 100;
    public static final int DEFAULT_COOLDOWN = 60;
    public static final int MIN_COOLDOWN = 10;
    public static final int MAX_COOLDOWN = 3600;
    public static final double MAX_MULTIPLIER = 5.0;
    public static final int MAX_LIST = 25;
    public static final int MAX_MESSAGE = 300;
    public static final String DEFAULT_MESSAGE = "{user} reached level {level}!";

    public ChatLevelConfig {
        ignoredChannels = ignoredChannels == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ignoredChannels));
        ignoredRoles = ignoredRoles == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ignoredRoles));
        channelMultipliers = channelMultipliers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(channelMultipliers));
        roleMultipliers = roleMultipliers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(roleMultipliers));
        announceMessage = announceMessage == null || announceMessage.isBlank() ? DEFAULT_MESSAGE : announceMessage;
    }

    public static ChatLevelConfig defaults(long guildId) {
        return new ChatLevelConfig(guildId, false, DEFAULT_MIN_XP, DEFAULT_MAX_XP, DEFAULT_COOLDOWN, false, null, DEFAULT_MESSAGE, false, Set.of(), Set.of(), Map.of(), Map.of());
    }

    /** "123:1.5,456:2" for storing a multiplier map. */
    public static String writeMultipliers(Map<Long, Double> map) {
        StringBuilder out = new StringBuilder();
        map.forEach((id, m) -> out.append(out.isEmpty() ? "" : ",").append(id).append(':').append(m));
        return out.toString();
    }

    public static Map<Long, Double> readMultipliers(String stored) {
        Map<Long, Double> map = new LinkedHashMap<>();
        if (stored == null || stored.isBlank()) return map;
        for (String part : stored.split(",")) {
            String[] pair = part.strip().split(":");
            if (pair.length != 2) continue;
            try {
                map.put(Long.parseLong(pair[0]), Double.parseDouble(pair[1]));
            } catch (NumberFormatException ignored) {
                // skip anything that isn't a multiplier
            }
        }
        return map;
    }

    /** The announcement with its variables filled in: {user} (a mention), {level}, {server}. */
    public static String fill(String template, String userMention, int level, String serverName) {
        return template.replace("{user}", userMention).replace("{level}", Integer.toString(level)).replace("{server}", serverName);
    }
}
