package com.younglings.bot.lookup;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * When RuneScape 3's resets happen, in UTC (the wiki's Distractions and Diversions page): daily every day at 00:00, weekly every Wednesday at 00:00,
 * monthly on the 1st at 00:00. These are the same rule the game has used for years, so they are worked out here; the schedule of the recurring events
 * is not, it is read from the wiki (see {@link DndTable}).
 */
public final class ResetTimes {
    private ResetTimes() {}

    /** The next daily reset strictly after {@code now}. */
    public static Instant nextDaily(Instant now) {
        return now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** The next Wednesday 00:00 UTC strictly after {@code now}. */
    public static Instant nextWeekly(Instant now) {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        return today.with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY)).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** The next 1st of a month at 00:00 UTC strictly after {@code now}. */
    public static Instant nextMonthly(Instant now) {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        return today.with(TemporalAdjusters.firstDayOfNextMonth()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
