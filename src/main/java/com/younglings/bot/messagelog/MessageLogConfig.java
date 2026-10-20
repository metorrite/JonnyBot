package com.younglings.bot.messagelog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A server's message and voice logs. Off until switched on. Each log is on when it has a channel: edits, deletes (single and bulk) and voice
 * join/leave/move. Message text is kept only so an edit or delete can show what it said before: for {@code retentionDays} days, never from direct messages,
 * never from {@code excludedChannels} (a thread counts as its channel), and never from bots.
 */
public record MessageLogConfig(long guildId, boolean enabled, int retentionDays, Set<Long> excludedChannels, Long editChannelId, Long deleteChannelId, Long voiceChannelId) {
    public static final int DEFAULT_RETENTION = 7;
    public static final int MIN_RETENTION = 1;
    public static final int MAX_RETENTION = 30;
    public static final int MAX_EXCLUDED = 50;

    public MessageLogConfig {
        excludedChannels = excludedChannels == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(excludedChannels));
    }

    public static MessageLogConfig defaults(long guildId) {
        return new MessageLogConfig(guildId, false, DEFAULT_RETENTION, Set.of(), null, null, null);
    }

    /** Whether message text has to be kept: only when something will show it. */
    public boolean cachesMessages() {
        return enabled && (editChannelId != null || deleteChannelId != null);
    }
}
