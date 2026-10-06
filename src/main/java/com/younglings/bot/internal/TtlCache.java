package com.younglings.bot.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A small in-memory cache with a time-to-live per entry and <em>single-flight</em> loading: when an entry is
 * missing or stale and many requests arrive together, one of them computes the value and the rest wait for it,
 * instead of every one of them running the same expensive queries. If the computation fails and an older value
 * exists, the old value is served rather than an error.
 */
final class TtlCache {
    private record Entry(Object value, long loadedAt) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    <T> T get(String key, long ttlMillis, Supplier<T> loader) {
        Entry entry = entries.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt() < ttlMillis) return (T) entry.value();

        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            Entry recheck = entries.get(key);
            if (recheck != null && System.currentTimeMillis() - recheck.loadedAt() < ttlMillis) return (T) recheck.value(); // someone else just loaded it

            try {
                T value = loader.get();
                entries.put(key, new Entry(value, System.currentTimeMillis()));
                return value;
            } catch (RuntimeException e) {
                if (recheck != null) return (T) recheck.value(); // stale beats broken
                throw e;
            }
        }
    }

    /** Forgets everything — call when something that changes many answers at once has changed (a privacy toggle, say). */
    void clear() {
        entries.clear();
    }

    /** Forgets entries whose key starts with {@code prefix}. */
    void invalidate(String prefix) {
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
