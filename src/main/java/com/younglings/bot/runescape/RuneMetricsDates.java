package com.younglings.bot.runescape;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Parses RuneMetrics' own raw activity-date string ({@code "23-Sep-2026 23:30"}, see
 * {@link PlayerActivity#date()}) for *relative* ordering only — never treated as an absolute instant
 * anywhere in this codebase, since the timezone it's actually in isn't documented (see
 * {@code RuneScapeDatabaseInitializer}'s {@code player_activity} comment). Comparing two of these to
 * each other is still safe without knowing that timezone, since every value comes from the same API in
 * the same unknown-but-consistent zone.
 */
public final class RuneMetricsDates {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    private RuneMetricsDates() {}

    /** {@link LocalDateTime#MIN} if {@code raw} doesn't parse — sorts first rather than throwing, so one malformed row doesn't break a whole batch's ordering. */
    public static LocalDateTime parse(String raw) {
        try {
            return LocalDateTime.parse(raw, FORMAT);
        } catch (DateTimeParseException e) {
            return LocalDateTime.MIN;
        }
    }
}
