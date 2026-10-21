package com.younglings.bot.rsnlink;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "At most N in any window" per key, exact (a sliding log of the last N times), in memory. Keys are lookups or ids, never raw values.
 * Idle keys are swept once the map grows, so a flood of one-off keys can't grow it without bound.
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
            if (log.size() >= max) return (int) Math.max(1, (log.peekFirst() + windowMillis - now + 999) / 1000);
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

    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || now - e.getValue().peekLast() >= windowMillis;
            }
        });
    }
}
