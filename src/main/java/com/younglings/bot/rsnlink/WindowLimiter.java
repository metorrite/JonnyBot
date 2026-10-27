package com.younglings.bot.rsnlink;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "At most N in any window" per key, exact (a sliding log of the last N times), in memory. Keys are lookups or ids, never raw values.
 * Idle keys are swept once the map grows, so a flood of one-off keys can't grow it without bound.
 * <p>
 * Per process: with N replicas each allows the full count. Used for the token, alias and web-account route limits, where that is fine
 * (SCALING-STATE.md M7). The security limits (codes, starts, DMs) use {@link SharedWindowLimiter} instead.
 */
final class WindowLimiter {
    private static final int SWEEP_THRESHOLD = 20_000;

    private final int max;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    WindowLimiter(LinkLimits.Window window) {
        this.max = window.max();
        this.windowMillis = window.window().toMillis();
    }

    /** Records one action for {@code key} and returns 0, or returns the seconds to wait (and records nothing) if the window is full. */
    int tryAcquire(String key, long now) {
        if (hits.size() > SWEEP_THRESHOLD) sweep(now);
        Deque<Long> log = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && now - log.peekFirst() >= windowMillis) log.pollFirst();
            if (log.size() >= max) return waitSeconds(log.peekFirst(), windowMillis, now);
            log.addLast(now);
            return 0;
        }
    }

    /** Whether one more would be allowed, without recording it. */
    boolean wouldAllow(String key, long now) {
        Deque<Long> log = hits.get(key);
        if (log == null) return true;
        synchronized (log) {
            while (!log.isEmpty() && now - log.peekFirst() >= windowMillis) log.pollFirst();
            return log.size() < max;
        }
    }

    /** Whole seconds (at least 1) until the oldest hit leaves the window, rounded up. Shared with {@link SharedWindowLimiter}. */
    static int waitSeconds(long oldestHit, long windowMillis, long now) {
        return (int) Math.max(1, (oldestHit + windowMillis - now + 999) / 1000);
    }

    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || now - e.getValue().peekLast() >= windowMillis;
            }
        });
    }
}
