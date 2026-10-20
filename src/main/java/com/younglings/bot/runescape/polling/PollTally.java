package com.younglings.bot.runescape.polling;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * How the day's RuneMetrics requests went, per tier (see {@link PollPriority}): requests that went out, requests answered from a recent snapshot
 * instead, every 429 RuneMetrics answered, and polls that failed or gave up. Counters only, kept in memory for the current UTC day (no per-request
 * records, no player names); {@code RuneMetricsBudget} saves a daily row so the history survives a restart.
 */
public final class PollTally {
    /** What happened to one request. */
    public enum Kind { SENT, REUSED, RATE_LIMITED, FAILED, GAVE_UP }

    /** One tier's counts for a day. */
    public record Counts(long sent, long reused, long rateLimited, long failed, long gaveUp) {
        static Counts of(long[] c) {
            return new Counts(c[0], c[1], c[2], c[3], c[4]);
        }
    }

    /** The counts of one UTC day. */
    public record Day(LocalDate day, Map<PollPriority, Counts> byPriority) {}

    private final Supplier<LocalDate> today;
    private LocalDate day;
    private final Map<PollPriority, long[]> counts = new EnumMap<>(PollPriority.class);

    public PollTally() {
        this(() -> LocalDate.now(java.time.ZoneOffset.UTC));
    }

    /** For tests: any clock. */
    PollTally(Supplier<LocalDate> today) {
        this.today = today;
        this.day = today.get();
    }

    public synchronized void record(PollPriority priority, Kind kind) {
        counts.computeIfAbsent(priority, p -> new long[Kind.values().length])[kind.ordinal()]++;
    }

    /** The current day so far. */
    public synchronized Day snapshot() {
        Map<PollPriority, Counts> copy = new EnumMap<>(PollPriority.class);
        counts.forEach((p, c) -> copy.put(p, Counts.of(c)));
        return new Day(day, copy);
    }

    /** If the UTC day has changed since the counters began, returns the finished day and starts counting the new one; otherwise null. */
    public synchronized Day rollIfNewDay() {
        LocalDate now = today.get();
        if (now.equals(day)) return null;
        Day finished = snapshot();
        counts.clear();
        day = now;
        return finished;
    }
}
