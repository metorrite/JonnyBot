package com.runeclans.bot.modlog;

/**
 * A server's moderation log: bans and unbans, and timeouts, posted to one channel. Each kind can be switched off on its own.
 */
public record ModLogConfig(long guildId, boolean enabled, Long channelId, boolean logBans, boolean logTimeouts) {
    public static ModLogConfig defaults(long guildId) {
        return new ModLogConfig(guildId, false, null, true, true);
    }
}
