package com.runeclans.bot.autopurge;

import java.time.Duration;
import java.time.Instant;

/**
 * A channel that cleans itself: messages older than {@code maxAgeMinutes} are deleted. Pinned messages are kept unless the rule
 * says otherwise. Discord only deletes in bulk messages under two weeks old, so that is the longest age allowed.
 */
public record AutoPurgeRule(long guildId, long channelId, int maxAgeMinutes, boolean keepPinned, Authors authors, boolean onlyWithAttachments, String containsText) {
    public static final int MIN_AGE_MINUTES = 60;
    public static final int MAX_TEXT_LENGTH = 100;

    /** Whose messages the rule deletes. ALL is the original behaviour. */
    public enum Authors { ALL, ONLY_BOTS, ONLY_HUMANS }

    /** What a rule needs to know about a message to decide, kept apart from Discord so it can be tested. {@code byBot} covers bot accounts and webhooks. */
    public record MessageFacts(Instant created, boolean pinned, boolean byBot, int attachments, String content) {}

    /** A rule with no filters: it deletes every message that is old enough, as auto-clean always has. */
    public AutoPurgeRule(long guildId, long channelId, int maxAgeMinutes, boolean keepPinned) {
        this(guildId, channelId, maxAgeMinutes, keepPinned, Authors.ALL, false, null);
    }

    public AutoPurgeRule {
        if (authors == null) authors = Authors.ALL;
        if (containsText != null && containsText.isBlank()) containsText = null;
        if (containsText != null) containsText = containsText.strip();
    }

    /** Whether any filter narrows what is deleted. */
    public boolean filtered() {
        return authors != Authors.ALL || onlyWithAttachments || containsText != null;
    }

    /** What is wrong with a filter, as words for the dashboard (null when it is fine). */
    public static String textProblem(String text) {
        if (text != null && text.strip().length() > MAX_TEXT_LENGTH) return "The text to look for can be at most " + MAX_TEXT_LENGTH + " characters.";
        return null;
    }
    public static final int MAX_AGE_MINUTES = 13 * 24 * 60;
    public static final int MAX_RULES_PER_GUILD = 25;

    public static boolean validAge(int minutes) {
        return minutes >= MIN_AGE_MINUTES && minutes <= MAX_AGE_MINUTES;
    }

    /** Messages created before this moment are due to go. */
    public Instant cutoff(Instant now) {
        return now.minus(Duration.ofMinutes(maxAgeMinutes));
    }

    /**
     * Whether a message is due on age and pin alone (the filters are not looked at): older than the cutoff, not pinned (unless pinned ones go too), and still young enough
     * for Discord to bulk delete it.
     */
    public boolean shouldDelete(Instant created, boolean pinned, Instant now) {
        if (pinned && keepPinned) return false;
        if (!created.isBefore(cutoff(now))) return false;
        return created.isAfter(now.minus(Duration.ofDays(14).minusMinutes(5)));
    }

    /**
     * The full decision for one message. The age, pin and Discord's two-week limit decide whether it is <em>due</em> ({@link #shouldDelete(Instant, boolean, Instant)});
     * the filters can only take messages out of that set, never add to it, and they all have to match (bots only AND has an attachment AND contains the text).
     * With no filter set this is exactly the old behaviour.
     */
    public boolean shouldDelete(MessageFacts message, Instant now) {
        if (!shouldDelete(message.created(), message.pinned(), now)) return false;
        return matchesFilters(message);
    }

    /** True when the message passes every filter (always true for an unfiltered rule). */
    public boolean matchesFilters(MessageFacts message) {
        if (authors == Authors.ONLY_BOTS && !message.byBot()) return false;
        if (authors == Authors.ONLY_HUMANS && message.byBot()) return false;
        if (onlyWithAttachments && message.attachments() < 1) return false;
        if (containsText != null) {
            String content = message.content() == null ? "" : message.content();
            if (!content.toLowerCase(java.util.Locale.ROOT).contains(containsText.toLowerCase(java.util.Locale.ROOT))) return false;
        }
        return true;
    }

    /** "6 hours", "1 day", "90 minutes", for the dashboard and logs. */
    public static String describe(int minutes) {
        if (minutes % (24 * 60) == 0) {
            int days = minutes / (24 * 60);
            return days + (days == 1 ? " day" : " days");
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " minutes";
    }
}
