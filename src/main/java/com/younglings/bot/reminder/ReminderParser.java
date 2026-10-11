package com.younglings.bot.reminder;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads how long to wait from what a person types: {@code 10m}, {@code 2h30m}, {@code 1d}, {@code 90 minutes},
 * {@code in 3 hours and 20 minutes}. Pure, so every spelling can be tested. Anything it can't read, or a length outside
 * {@link #MIN} to {@link #MAX}, comes back empty.
 */
public final class ReminderParser {
    private ReminderParser() {}

    public static final Duration MIN = Duration.ofMinutes(1);
    public static final Duration MAX = Duration.ofDays(365);

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(weeks?|w|days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])");

    public static Optional<Duration> parse(String raw) {
        if (raw == null) return Optional.empty();
        String text = raw.toLowerCase(Locale.ROOT).strip().replaceFirst("^in\\s+", "").replace(",", " ").replace(" and ", " ");
        if (text.isEmpty()) return Optional.empty();

        Matcher matcher = PART.matcher(text);
        Duration total = Duration.ZERO;
        int end = 0;
        while (matcher.find()) {
            // only separators allowed between the parts, so "10m banana 5m" isn't quietly read as 15 minutes
            if (!text.substring(end, matcher.start()).isBlank()) return Optional.empty();
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (amount > 100_000) return Optional.empty();
            total = total.plus(unit(matcher.group(2)).multipliedBy(amount));
            end = matcher.end();
        }
        if (end == 0 || !text.substring(end).isBlank()) return Optional.empty();
        if (total.compareTo(MIN) < 0 || total.compareTo(MAX) > 0) return Optional.empty();
        return Optional.of(total);
    }

    private static Duration unit(String name) {
        return switch (name.charAt(0)) {
            case 'w' -> Duration.ofDays(7);
            case 'd' -> Duration.ofDays(1);
            case 'h' -> Duration.ofHours(1);
            case 'm' -> Duration.ofMinutes(1);
            default -> Duration.ofSeconds(1);
        };
    }
}
