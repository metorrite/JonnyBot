package com.younglings.bot.autopurge;

import java.time.Duration;
import java.time.Instant;

/**
 * A channel that cleans itself: messages older than {@code maxAgeMinutes} are deleted. Pinned messages are kept unless the rule
 * says otherwise. Discord only deletes in bulk messages under two weeks old, so that is the longest age allowed.
 */
public record AutoPurgeRule(long guildId, long channelId, int maxAgeMinutes, boolean keepPinned) {
    public static final int MIN_AGE_MINUTES = 60;
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
     * Whether a message should be deleted: older than the cutoff, not pinned (unless pinned ones go too), and still young enough
     * for Discord to bulk delete it.
     */
    public boolean shouldDelete(Instant created, boolean pinned, Instant now) {
        if (pinned && keepPinned) return false;
        if (!created.isBefore(cutoff(now))) return false;
        return created.isAfter(now.minus(Duration.ofDays(14).minusMinutes(5)));
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
