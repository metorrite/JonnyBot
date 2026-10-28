package com.runeclans.bot.runescape;

/**
 * Stops asking RuneMetrics for a while once it has failed many times in a row, so an outage (or the API's retirement) costs one probe every
 * few minutes instead of one doomed request, retry and warning per player. Only outright failures count; a rate limit, a private profile or
 * an unknown name is RuneMetrics answering. After the pause one request is let through: success closes the breaker, failure pauses again.
 * Pure (times are passed in), so it can be tested.
 */
final class RuneMetricsBreaker {
    static final int FAILURES_TO_OPEN = 20;
    static final long PAUSE_MS = 10 * 60_000L;

    private int failuresInARow;
    private long pausedUntil;
    private boolean open;

    /** Whether a request may go out now. While paused, false; once the pause is over, true (that request is the probe). */
    synchronized boolean allow(long now) {
        return !open || now >= pausedUntil;
    }

    /** RuneMetrics answered. True when this closed an open breaker (worth one log line). */
    synchronized boolean recordAnswer() {
        failuresInARow = 0;
        boolean wasOpen = open;
        open = false;
        return wasOpen;
    }

    /** RuneMetrics failed outright. True when this opened the breaker (worth one log line); a failed probe pauses again quietly. */
    synchronized boolean recordFailure(long now) {
        failuresInARow++;
        if (open) {
            pausedUntil = now + PAUSE_MS;
            return false;
        }
        if (failuresInARow < FAILURES_TO_OPEN) return false;
        open = true;
        pausedUntil = now + PAUSE_MS;
        return true;
    }

    synchronized boolean isOpen() {
        return open;
    }
}
