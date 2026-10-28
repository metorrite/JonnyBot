package com.runeclans.bot.lookup;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

/**
 * The reminders a server can switch on for the RuneScape timers, each posted to a channel of the server's choice with an optional role pinged. All off by default.
 */
public enum ReminderKind {
    VOS("vos", "Voice of Seren", "Posted at the top of every hour, when the two boosted districts change."),
    DAILY_RESET("reset-daily", "Daily reset", "Posted at 00:00 UTC every day, when daily challenges and daily D&Ds come back."),
    WEEKLY_RESET("reset-weekly", "Weekly reset", "Posted at 00:00 UTC every Wednesday, when weekly D&Ds come back."),
    MONTHLY_RESET("reset-monthly", "Monthly reset", "Posted at 00:00 UTC on the 1st of every month, when monthly D&Ds come back.");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private final String key;
    private final String title;
    private final String description;

    ReminderKind(String key, String title, String description) {
        this.key = key;
        this.title = title;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public static Optional<ReminderKind> ofKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /** Names the occasion this reminder is for at {@code now}, or null when nothing is due: posting the same key twice is what the scheduler prevents. */
    public String dueKey(Instant now) {
        return switch (this) {
            // The hour just started; ten minutes of grace in case a tick was late or the crowdsourced report lags.
            case VOS -> now.atOffset(ZoneOffset.UTC).getMinute() < 10 ? HOUR.format(now) : null;
            // Resets happen at 00:00 UTC; same ten minutes of grace.
            case DAILY_RESET -> justAfterMidnight(now) ? DAY.format(now) : null;
            case WEEKLY_RESET -> justAfterMidnight(now) && now.atOffset(ZoneOffset.UTC).getDayOfWeek() == DayOfWeek.WEDNESDAY ? DAY.format(now) : null;
            case MONTHLY_RESET -> justAfterMidnight(now) && now.atOffset(ZoneOffset.UTC).getDayOfMonth() == 1 ? DAY.format(now) : null;
        };
    }

    private static boolean justAfterMidnight(Instant now) {
        var t = now.atOffset(ZoneOffset.UTC);
        return t.getHour() == 0 && t.getMinute() < 10;
    }
}
