package com.younglings.bot.runescape;

import java.time.Duration;

/**
 * Computes the per-player delay that spreads {@code count} polls evenly across a target
 * {@code window} — e.g. 60 players across a 3-hour window means one poll every 3 minutes, 360 players
 * means one every 30 seconds — instead of bursting everyone at once and then going quiet until the
 * next cycle. Never returns less than {@code floorMs}, the minimum safe gap between calls to the
 * RuneMetrics API ({@link com.younglings.bot.config.BotConfig#getRunescapePollDelaySeconds()}); for a
 * roster large enough that an even spread would beat that floor, the floor wins and the window
 * guarantee slips slightly rather than hammering the API.
 */
public final class PollPacing {
    private PollPacing() {}

    public static long evenSpreadDelayMs(int count, Duration window, long floorMs) {
        if (count <= 0) return floorMs;
        long even = window.toMillis() / count;
        return Math.max(even, floorMs);
    }
}
