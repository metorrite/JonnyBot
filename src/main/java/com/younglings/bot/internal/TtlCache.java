package com.younglings.bot.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A small in-memory cache with a time-to-live per entry and <em>single-flight</em> loading: when an entry is
 * missing or stale and many requests arrive together, one of them computes the value and the rest wait for it,
 * instead of every one of them running the same expensive queries. If the computation fails and an older value
 * exists, the old value is served rather than an error.
 *
 * <p>Keys often carry a query string (a player name, a user id), so the set of keys keeps growing. To keep memory
 * in step with live traffic, an entry nobody has asked for in {@link #GRACE_MS} past its time-to-live is dropped by
 * a sweep that runs at most once every {@link #SWEEP_EVERY_MS}, on a load (no extra thread). Inside that window an
 * expired entry is still kept as the fallback for a failed load. Lock objects only live while someone holds or waits
 * for them.
 */
final class TtlCache {
    /** How long an expired entry is kept as the "stale beats broken" fallback before a sweep may drop it. */
    static final long GRACE_MS = 60 * 60_000L;
    /** The sweep runs at most this often. */
    static final long SWEEP_EVERY_MS = 60_000L;

    private record Entry(Object value, long loadedAt, long ttlMillis) {
        boolean droppable(long now) {
            if (ttlMillis > Long.MAX_VALUE - GRACE_MS) return false; // "practically forever"
            return now - loadedAt > ttlMillis + GRACE_MS;
        }
    }

    /** One per key while it is being loaded; {@code holders} only changes inside the map's per-key compute. */
    private static final class Lock {
        int holders;
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final AtomicLong lastSweep;

    TtlCache() {
        this(System::currentTimeMillis);
    }

    /** For tests: a cache that reads the time from {@code clock}. */
    TtlCache(LongSupplier clock) {
        this.clock = clock;
        this.lastSweep = new AtomicLong(clock.getAsLong());
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, long ttlMillis, Supplier<T> loader) {
        Entry entry = entries.get(key);
        long now = clock.getAsLong();
        if (entry != null && now - entry.loadedAt() < ttlMillis) return (T) entry.value();

        Lock lock = locks.compute(key, (k, held) -> {
            Lock l = held == null ? new Lock() : held;
            l.holders++;
            return l;
        });
        try {
            synchronized (lock) {
                Entry recheck = entries.get(key);
                if (recheck != null && clock.getAsLong() - recheck.loadedAt() < ttlMillis) return (T) recheck.value(); // someone else just loaded it

                try {
                    T value = loader.get();
                    long loadedAt = clock.getAsLong();
                    entries.put(key, new Entry(value, loadedAt, ttlMillis));
                    sweepIfDue(loadedAt);
                    return value;
                } catch (RuntimeException e) {
                    if (recheck != null) return (T) recheck.value(); // stale beats broken
                    throw e;
                }
            }
        } finally {
            locks.computeIfPresent(key, (k, l) -> --l.holders == 0 ? null : l);
        }
    }

    private void sweepIfDue(long now) {
        long last = lastSweep.get();
        if (now - last < SWEEP_EVERY_MS || !lastSweep.compareAndSet(last, now)) return;
        // Removes an entry only if it is still the one tested, so a value stored meanwhile survives.
        entries.entrySet().removeIf(e -> e.getValue().droppable(now));
    }

    /** Forgets everything — call when something that changes many answers at once has changed (a privacy toggle, say). */
    void clear() {
        entries.clear();
    }

    /** Forgets entries whose key starts with {@code prefix}. */
    void invalidate(String prefix) {
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** For tests. */
    int size() {
        return entries.size();
    }

    /** For tests. */
    int lockCount() {
        return locks.size();
    }
}
