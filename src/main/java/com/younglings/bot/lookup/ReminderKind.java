package com.younglings.bot.lookup;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

/**
 * The reminders a server can switch on for the RuneScape timers, each posted to a channel of the server's choice with an optional role pinged. All off by default.
 */
public enum ReminderKind {
    VOS("vos", "Voice of Seren", "Posted at the top of every hour, when the two boosted districts change.");

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
        };
    }
}
