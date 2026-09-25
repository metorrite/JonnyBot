package com.younglings.bot.runescape;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Everything {@link MonthlyRecapRenderer} needs for one player's month-to-date recap image. See
 * {@link MonthlyRecapService} for how each field is derived, including the caveats on
 * {@code timesCapped}/{@code mostChallenged} (both parsed from free-text activity strings, not a
 * documented RuneMetrics taxonomy).
 */
public record MonthlyRecapStats(String rsn, String monthLabel, OffsetDateTime rangeStart, OffsetDateTime rangeEnd,
                                 long totalXpGained, int timesCapped, String mostChallenged, int mostChallengedCount,
                                 Map<Integer, Long> skillXpGained) {
}
