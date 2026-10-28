package com.runeclans.bot.goodbye;

/**
 * A server's goodbye message: a line posted in a channel when someone leaves. Plain text with the same {@code {variables}} the welcome
 * message uses; {@code {user}} shows the leaver's name in bold, because a mention of someone who has left would only show a bare number.
 */
public record GoodbyeConfig(long guildId, boolean enabled, Long channelId, String content) {
    public static final int MAX_CONTENT = 1500;
    public static final String DEFAULT_CONTENT = "**{username}** has left **{server}**. We're now {count} members.";

    public static GoodbyeConfig defaults(long guildId) {
        return new GoodbyeConfig(guildId, false, null, DEFAULT_CONTENT);
    }
}
