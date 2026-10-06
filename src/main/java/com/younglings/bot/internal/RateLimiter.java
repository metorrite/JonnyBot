package com.younglings.bot.internal;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A token-bucket limiter keyed by whatever the caller chooses (a user id, an action name, both). Each key gets
 * {@code capacity} actions up front, and earns one more every {@code refillMillis}; an action that finds the
 * bucket empty is refused until a token has dripped back. That allows a short, natural burst (a few quick
 * clicks) but flattens a sustained flood.
 * <p>
 * In memory only: a restart gives everyone a full bucket again, which is fine for protection against button
 * mashing. Idle buckets are swept occasionally so the map can't grow without bound.
 */
final class RateLimiter {
    private static final int SWEEP_THRESHOLD = 5_000;

    private final int capacity;
    private final long refillMillis;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }

    RateLimiter(int capacity, long refillMillis) {
        this.capacity = capacity;
        this.refillMillis = refillMillis;
    }

    /** Takes a token for {@code key} if there is one. */
    boolean tryAcquire(String key) {
        return tryAcquire(key, System.currentTimeMillis());
    }

    boolean tryAcquire(String key, long now) {
        if (buckets.size() > SWEEP_THRESHOLD) sweep(now);

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.updatedAt) / (double) refillMillis);
            bucket.updatedAt = now;
            if (bucket.tokens < 1) return false;
            bucket.tokens -= 1;
            return true;
        }
    }

    /** Drops buckets that have been idle long enough to be full again — they'd be recreated full anyway. */
    private void sweep(long now) {
        buckets.entrySet().removeIf(e -> now - e.getValue().updatedAt > refillMillis * capacity);
    }
}
