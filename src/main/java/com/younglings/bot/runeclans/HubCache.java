package com.younglings.bot.runeclans;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A small in-memory cache of hub answers (runeclans-hub PLATFORM-DESIGN 9.2, 9.4): clan settings, switches and the like, kept {@link #FRESH}
 * and emptied the moment the hub says the item changed ({@link #evictTag}, from a webhook or the feed). It holds at most {@link #MAX_ENTRIES}
 * answers (least recently used go first), so it stays small however many servers the bot is in. While the hub is unreachable an answer
 * up to {@link #STALE_LIMIT} old can still be used, marked stale ("may be a few minutes old"). Never a store: the data lives in the hub.
 */
final class HubCache {
    static final int MAX_ENTRIES = 2_000;
    static final Duration FRESH = Duration.ofMinutes(2);
    static final Duration STALE_LIMIT = Duration.ofMinutes(15);

    /** A cached answer and whether it is past {@link #FRESH}. */
    record Hit(RuneClansHubClient.Answer answer, boolean stale) {}

    private record Entry(RuneClansHubClient.Answer answer, Set<String> tags, Instant storedAt) {}

    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries;

    HubCache(int maxEntries, Clock clock) {
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > HubCache.this.maxEntries;
            }
        };
    }

    /** The fresh answer for {@code key}, or null. */
    synchronized RuneClansHubClient.Answer fresh(String key) {
        Hit hit = lookup(key);
        return hit == null || hit.stale() ? null : hit.answer();
    }

    /** The answer for {@code key} while it is under {@link #STALE_LIMIT} old (marked stale after {@link #FRESH}), or null. */
    synchronized Hit lookup(String key) {
        Entry e = entries.get(key);
        if (e == null) return null;
        Duration age = Duration.between(e.storedAt(), clock.instant());
        if (age.compareTo(STALE_LIMIT) > 0) {
            entries.remove(key);
            return null;
        }
        return new Hit(e.answer(), age.compareTo(FRESH) > 0);
    }

    /** Keeps {@code answer} under {@code key}; {@code tags} name the hub items it shows ({@code clan:<id>}), for {@link #evictTag}. */
    synchronized void put(String key, RuneClansHubClient.Answer answer, Set<String> tags) {
        entries.put(key, new Entry(answer, Set.copyOf(tags), clock.instant()));
    }

    synchronized void evict(String key) {
        entries.remove(key);
    }

    /** Drops every answer showing {@code tag}. Returns how many. */
    synchronized int evictTag(String tag) {
        int before = entries.size();
        entries.values().removeIf(e -> e.tags().contains(tag));
        return before - entries.size();
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }
}
