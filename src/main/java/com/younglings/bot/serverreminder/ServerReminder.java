package com.younglings.bot.serverreminder;

import java.time.Duration;
import java.time.Instant;

/**
 * A reminder staff post in a channel, once or again and again, optionally pinging one role. (A personal {@code /remindme} goes to one person's DMs instead.)
 * {@code every} is null for a one-off. Pure helpers here; the posting is in {@link ServerReminderService}.
 */
public record ServerReminder(long id, long guildId, long channelId, String text, Long roleId, Duration every, Instant nextAt, Status status, long createdBy,
                             String createdByName, Instant lastSentAt, int sendCount, String error) {
    public static final int MAX_TEXT = 1000;
    public static final int MAX_ACTIVE_PER_GUILD = 20;
    /** A repeating reminder can't fire more often than this, so a typo can't turn into spam. */
    public static final Duration MIN_EVERY = Duration.ofHours(1);
    public static final Duration MAX_EVERY = Duration.ofDays(60);
    public static final Duration MAX_AHEAD = Duration.ofDays(365);

    public enum Status { ACTIVE, DONE, CANCELLED, FAILED }

    public boolean repeating() {
        return every != null;
    }

    /**
     * When a repeating reminder that was due at {@code due} fires next: the first moment after {@code now} that is a whole number of intervals after {@code due}.
     * If the bot was down for several intervals this gives one run, not a catch-up burst.
     */
    public static Instant nextAfter(Instant due, Duration every, Instant now) {
        long missed = Duration.between(due, now).toMillis() / every.toMillis();
        Instant next = due.plus(every.multipliedBy(Math.max(1, missed + 1)));
        return next.isAfter(now) ? next : next.plus(every);
    }

    /** What is wrong with a reminder's text, or null. */
    public static String textProblem(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return "Say what the reminder is.";
        if (t.length() > MAX_TEXT) return "A reminder can be at most " + MAX_TEXT + " characters.";
        return null;
    }

    /** What is wrong with the repeat interval, or null (a null interval is a one-off). */
    public static String everyProblem(Duration every) {
        if (every == null) return null;
        if (every.compareTo(MIN_EVERY) < 0) return "A repeating reminder can't come more often than every hour.";
        if (every.compareTo(MAX_EVERY) > 0) return "A repeating reminder can't wait more than 60 days between posts.";
        return null;
    }
}
