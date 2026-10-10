package com.younglings.bot.runescape.polling;

import java.util.function.LongSupplier;

/**
 * The one budget of RuneMetrics requests the whole bot spends from: a token bucket that holds up to {@code capacity}
 * requests and refills one every {@code baseIntervalMs}. A quiet bot therefore stores up a burst that an "Update now"
 * can use at once, while a busy one is held to the sustained rate.
 * <p>
 * Measured against the live API (see {@code tools/RuneMetricsRateTest.java}) the limit behaves like exactly this, per
 * IP address, so the bucket mirrors it instead of spacing every request a fixed distance apart. It also reacts to what
 * RuneMetrics says: an HTTP 429 empties the bucket and doubles the refill interval (up to {@link #MAX_MULTIPLIER} times
 * the base), and {@link #CALM_REQUESTS_TO_RELAX} requests in a row without one halve it again, so a rate limit slows
 * everything at once and the bot drifts back to its normal pace after it stops.
 * <p>
 * Plain logic with an injectable clock, so it is tested without sleeping. Not thread-safe by itself: {@link PollEngine}
 * calls it under its own lock.
 */
public final class RateBucket {
    static final int MAX_MULTIPLIER = 8;
    static final int CALM_REQUESTS_TO_RELAX = 10;

    private final double capacity;
    private final long baseIntervalMs;
    private final LongSupplier nowMs;

    private double tokens;
    private long lastRefillAt;
    private int multiplier = 1;
    private int calmStreak;

    /** Starts full, so the first {@code capacity} requests after a restart go straight out. */
    public RateBucket(int capacity, long baseIntervalMs, LongSupplier nowMs) {
        this.capacity = Math.max(1, capacity);
        this.baseIntervalMs = Math.max(1, baseIntervalMs);
        this.nowMs = nowMs;
        this.tokens = this.capacity;
        this.lastRefillAt = nowMs.getAsLong();
    }

    /** A bucket for a steady rate given as requests per minute. */
    public static RateBucket perMinute(int requestsPerMinute, int burst, LongSupplier nowMs) {
        return new RateBucket(burst, Math.round(60_000.0 / Math.max(1, requestsPerMinute)), nowMs);
    }

    /** How long until a request may be sent: 0 when a token is available now. */
    public long waitMs() {
        refill();
        if (tokens >= 1) return 0;
        return (long) Math.ceil((1 - tokens) * intervalMs());
    }

    /** Spends a token for a request that is being sent now (the caller checked {@link #waitMs()} first). */
    public void consume() {
        refill();
        tokens -= 1;
    }

    /** Tells the bucket how the request it paid for turned out. */
    public void recordResult(boolean wasRateLimited) {
        refill();
        if (wasRateLimited) {
            calmStreak = 0;
            multiplier = Math.min(multiplier * 2, MAX_MULTIPLIER);
            // The bucket was evidently not as full as it thought, so nothing is spare until a whole new interval passes.
            tokens = Math.min(tokens, 0);
        } else if (multiplier > 1 && ++calmStreak >= CALM_REQUESTS_TO_RELAX) {
            multiplier = Math.max(1, multiplier / 2);
            calmStreak = 0;
        }
    }

    public double tokens() {
        refill();
        return tokens;
    }

    public long intervalMs() {
        return baseIntervalMs * multiplier;
    }

    public int multiplier() {
        return multiplier;
    }

    public double capacity() {
        return capacity;
    }

    private void refill() {
        long now = nowMs.getAsLong();
        long elapsed = Math.max(0, now - lastRefillAt);
        lastRefillAt = now;
        tokens = Math.min(capacity, tokens + elapsed / (double) intervalMs());
    }
}
