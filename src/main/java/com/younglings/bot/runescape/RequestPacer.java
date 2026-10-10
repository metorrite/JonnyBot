package com.younglings.bot.runescape;

import java.util.function.LongSupplier;

/**
 * One shared pace for every request the bot sends to RuneMetrics, however many threads or servers are asking.
 * <p>
 * Each polling pass sleeps its own delay between requests, which keeps one pass polite but says nothing about two
 * passes (the two polling tiers, a manual update, the daily clan sync) running at once: their requests simply add
 * up on the same IP address. This hands out request slots instead, so that, bot-wide, no two requests start closer
 * together than the current interval.
 * <p>
 * The interval starts at {@code baseIntervalMs} and reacts to what RuneMetrics says. Every HTTP 429 doubles it (up
 * to {@link #MAX_MULTIPLIER} times the base) and pushes the next slot out by the new interval, and each
 * {@link #CALM_REQUESTS_TO_RELAX} requests in a row without a 429 halve it again, so a rate limit slows everything
 * down at once and the bot drifts back to its normal pace after it stops. Passes that are already slower than the
 * interval (the usual case: a 3-hour window over a clan is one request every couple of minutes) never wait at all.
 * <p>
 * Plain logic with an injectable clock so it can be tested without sleeping; {@link #awaitSlot()} is the only part
 * that sleeps.
 */
public final class RequestPacer {
    static final int MAX_MULTIPLIER = 8;
    static final int CALM_REQUESTS_TO_RELAX = 20;

    /** What the pacer has seen since the bot started; subtract two snapshots to get one pass's share. */
    public record Stats(long requests, long rateLimited, long waitedMs, int multiplier, long intervalMs) {
        public Stats minus(Stats earlier) {
            return new Stats(requests - earlier.requests, rateLimited - earlier.rateLimited, waitedMs - earlier.waitedMs, multiplier, intervalMs);
        }

        /** One line for the console: {@code 64 requests, 3 rate-limited, 12.4s spent waiting for a slot, interval 2000ms (x1)}. */
        public String describe() {
            return "%d request(s), %d rate-limited, %.1fs spent waiting for a slot, interval now %dms (x%d)"
                    .formatted(requests, rateLimited, waitedMs / 1000.0, intervalMs, multiplier);
        }
    }

    private final long baseIntervalMs;
    private final LongSupplier nowMs;

    private long nextSlotAt;
    private int multiplier = 1;
    private int calmStreak;
    private long requests;
    private long rateLimited;
    private long waitedMs;

    public RequestPacer(long baseIntervalMs, LongSupplier nowMs) {
        this.baseIntervalMs = Math.max(0, baseIntervalMs);
        this.nowMs = nowMs;
        this.nextSlotAt = nowMs.getAsLong();
    }

    /** Claims the next slot and returns how long the caller must wait before sending (0 if it can go now). */
    public synchronized long reserveSlot() {
        long now = nowMs.getAsLong();
        long start = Math.max(now, nextSlotAt);
        nextSlotAt = start + intervalMs();
        requests++;
        long wait = start - now;
        waitedMs += wait;
        return wait;
    }

    /** Claims a slot and sleeps until it comes up. */
    public void awaitSlot() throws InterruptedException {
        long wait = reserveSlot();
        if (wait > 0) Thread.sleep(wait);
    }

    /** Tells the pacer how the request it paced turned out, so it can slow down after a 429 and relax after a calm run. */
    public synchronized void recordResult(boolean wasRateLimited) {
        if (wasRateLimited) {
            rateLimited++;
            calmStreak = 0;
            multiplier = Math.min(multiplier * 2, MAX_MULTIPLIER);
            // everything already queued behind this one waits out the new, longer interval too
            nextSlotAt = Math.max(nextSlotAt, nowMs.getAsLong() + intervalMs());
        } else if (multiplier > 1 && ++calmStreak >= CALM_REQUESTS_TO_RELAX) {
            multiplier = Math.max(1, multiplier / 2);
            calmStreak = 0;
        }
    }

    public synchronized Stats stats() {
        return new Stats(requests, rateLimited, waitedMs, multiplier, intervalMs());
    }

    private long intervalMs() {
        return baseIntervalMs * multiplier;
    }
}
