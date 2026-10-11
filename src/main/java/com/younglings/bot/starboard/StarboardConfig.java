package com.younglings.bot.starboard;

/**
 * A server's starboard: messages that collect enough of one reaction (a star, by default) are copied into a showcase channel. Good for
 * a drops-and-achievements channel, where members nominate the best moments by reacting.
 */
public record StarboardConfig(long guildId, boolean enabled, Long channelId, String emoji, int threshold, boolean selfStar) {
    public static final String DEFAULT_EMOJI = "⭐";
    public static final int DEFAULT_THRESHOLD = 3;
    public static final int MAX_THRESHOLD = 25;

    public static StarboardConfig defaults(long guildId) {
        return new StarboardConfig(guildId, false, null, DEFAULT_EMOJI, DEFAULT_THRESHOLD, false);
    }
}
