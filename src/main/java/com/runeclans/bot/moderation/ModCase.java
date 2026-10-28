package com.runeclans.bot.moderation;

import java.time.Instant;

/**
 * One numbered moderation case. {@code caseNo} counts per server (the first case of every server is 1). {@code userName} and
 * {@code moderatorName} are what they were called at the time, so a case still reads well after someone leaves.
 *
 * @param durationMinutes only for TIMEOUT, null otherwise
 * @param source          where it came from: COMMAND, WARN, AUTOPUNISH, AUTOMOD, HONEYPOT or SERVER_TAG
 * @param originalReason  the reason as first written, only when it was edited
 */
public record ModCase(long guildId, long caseNo, long userId, String userName, long moderatorId, String moderatorName, ModAction action,
                      String reason, Integer durationMinutes, String source, Instant createdAt, Long editedBy, Instant editedAt, String originalReason) {
    public static final String COMMAND = "COMMAND";
    public static final String WARN = "WARN";
    public static final String AUTOPUNISH = "AUTOPUNISH";
    public static final String AUTOMOD = "AUTOMOD";
    public static final String HONEYPOT = "HONEYPOT";
    public static final String SERVER_TAG = "SERVER_TAG";
}
