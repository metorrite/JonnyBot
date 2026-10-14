package com.younglings.bot.tracking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * The Clan Citadel's week. The game resets at 00:00 UTC on Wednesday, but the Citadel's weekly cap resets ten minutes later, at 00:10, so a Citadel week
 * runs from one Wednesday 00:10 UTC to the next. Everything that groups visits and caps into weeks uses this one definition, and groups by the time the
 * event really happened (see {@code RuneMetricsDates}), so someone who caps at 00:05 counts for the week that just ended and someone who caps at 00:15
 * for the one that just began, whenever the bot happens to notice.
 */
public final class CitadelWeek {
    /** The time of day, UTC, at which the Citadel's week turns over on a Wednesday. */
    public static final LocalTime RESET = LocalTime.of(0, 10);

    private CitadelWeek() {}

    /** The reset that begins the week of {@code wednesday}. */
    public static OffsetDateTime resetOn(LocalDate wednesday) {
        return wednesday.atTime(RESET).atOffset(ZoneOffset.UTC);
    }

    /** {@code [start, end)} of the week that ends at the reset on {@code endWednesday}. */
    public static OffsetDateTime[] windowEndingOn(LocalDate endWednesday) {
        OffsetDateTime end = resetOn(endWednesday);
        return new OffsetDateTime[]{end.minusDays(7), end};
    }

    /** The most recent reset at or before {@code now}. */
    public static OffsetDateTime lastReset(OffsetDateTime now) {
        OffsetDateTime utc = now.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime candidate = resetOn(utc.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY)));
        return candidate.isAfter(utc) ? candidate.minusDays(7) : candidate;
    }

    /** The week that most recently finished: the one before the latest reset. */
    public static OffsetDateTime[] lastCompleted(OffsetDateTime now) {
        OffsetDateTime end = lastReset(now);
        return new OffsetDateTime[]{end.minusDays(7), end};
    }

    /** The week in progress: from the latest reset to the next one. Its end stays the same for all seven days, which makes it a stable key for once-a-week awards. */
    public static OffsetDateTime[] current(OffsetDateTime now) {
        OffsetDateTime start = lastReset(now);
        return new OffsetDateTime[]{start, start.plusDays(7)};
    }

    /** The Wednesday (the date of the reset) that begins the week containing the moment {@code at}. */
    public static LocalDate weekStartOf(OffsetDateTime at) {
        return lastReset(at).toLocalDate();
    }
}
