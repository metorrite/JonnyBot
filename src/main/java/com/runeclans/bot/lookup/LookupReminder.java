package com.runeclans.bot.lookup;

/** One server's settings for one {@link ReminderKind}. {@code lastKey} is the occasion last posted, so a restart never posts twice. */
public record LookupReminder(long guildId, ReminderKind kind, boolean enabled, Long channelId, Long roleId, String lastKey) {
    public static LookupReminder off(long guildId, ReminderKind kind) {
        return new LookupReminder(guildId, kind, false, null, null, "");
    }
}
