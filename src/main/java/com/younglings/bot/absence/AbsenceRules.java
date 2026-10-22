package com.younglings.bot.absence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The rules for a leave-of-absence notice (T-190), in one place for the slash command, the website and the tests: a notice runs to the end of
 * its {@code until} day (UTC), at most {@value #MAX_DAYS} days ahead, with an optional reason of at most {@value #MAX_REASON} characters.
 */
public final class AbsenceRules {
    private AbsenceRules() {}

    public static final int MAX_DAYS = 90;
    public static final int MAX_REASON = 100;

    /** Why a notice can't be saved, or null when it can. */
    public static String problem(LocalDate until, String reason, LocalDate today) {
        if (until == null) return "Pick the day you'll be back.";
        if (until.isBefore(today)) return "That day has already passed.";
        if (until.isAfter(today.plusDays(MAX_DAYS))) return "A notice can run for at most " + MAX_DAYS + " days. Set a new one when it ends.";
        if (reason != null && reason.strip().length() > MAX_REASON) return "Keep the reason to " + MAX_REASON + " characters.";
        return null;
    }

    /** The reason as stored: trimmed, single line, never null. */
    public static String cleanReason(String reason) {
        return reason == null ? "" : reason.replaceAll("\\s+", " ").strip();
    }

    /** Whether a notice that runs to {@code until} still covers {@code today}. */
    public static boolean active(LocalDate until, LocalDate today) {
        return !until.isBefore(today);
    }

    /** "Sun 3 Nov 2026": how a notice's last day is written in Discord and on the dashboard's lists. */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    /** The last day a notice of {@code days} days covers, counting today as day one. */
    public static LocalDate untilForDays(int days, LocalDate today) {
        return today.plusDays(Math.max(1, days) - 1L);
    }
}
