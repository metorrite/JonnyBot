package com.younglings.bot.notice;

import java.time.OffsetDateTime;
import java.util.Set;

/** A message from the bot's owner shown at the top of every server's dashboard. */
public record Notice(long id, String severity, String body, OffsetDateTime createdAt) {
    /** info is general news, warning is something to know about, issue is something broken right now. */
    public static final Set<String> SEVERITIES = Set.of("info", "warning", "issue");
    public static final int MAX_BODY = 500;
    public static final int MAX_ACTIVE = 10;
}
