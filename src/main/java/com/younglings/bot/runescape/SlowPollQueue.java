package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds RSNs that just got HTTP 429'd by RuneMetrics, so {@link SlowPollScheduler} can retry them on
 * their own slow, backing-off cadence instead of the caller treating a rate-limit exactly like any
 * other failed poll and moving on at the normal (much faster) pacing — which would only make the rate
 * limit worse. Purely in-memory — resets on restart, which is fine: a queued retry is a "catch up
 * eventually" courtesy, not something worth persisting across a deploy.
 * <p>
 * Kept separate from {@link RuneScapeStatsService} (which enqueues into this) and
 * {@link SlowPollScheduler} (which drains it by calling back into the service) specifically so neither
 * of those two ever needs to depend on the other directly.
 */
@BService
public class SlowPollQueue {
    private static final Logger log = LoggerFactory.getLogger(SlowPollQueue.class);

    // 2m, 4m, 8m, 16m, 32m, capped at 60m from then on — a repeat offender backs off further each
    // time instead of getting hammered again at the same interval that just got it rate-limited.
    private static final Duration BASE_BACKOFF = Duration.ofMinutes(2);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(60);

    public record Entry(long guildId, String rsn, int attempt) {}

    private record QueuedEntry(long guildId, String rsn, int attempt, Instant nextRetryAt) {}

    // Keyed by guild+lowercased rsn so a repeat 429 for the same player updates its existing entry
    // (fresh backoff, incremented attempt) instead of piling up duplicates for the same name.
    private final Map<String, QueuedEntry> queue = new LinkedHashMap<>();

    /** {@code retryAfterHint} is the server's own {@code Retry-After} value, if it sent one — never waited *less* than that, even on the first attempt. */
    public synchronized void enqueue(long guildId, String rsn, Duration retryAfterHint) {
        String key = key(guildId, rsn);
        int attempt = queue.containsKey(key) ? queue.get(key).attempt() + 1 : 1;
        Duration backoff = backoffFor(attempt);
        if (retryAfterHint != null && retryAfterHint.compareTo(backoff) > 0) backoff = retryAfterHint;

        queue.put(key, new QueuedEntry(guildId, rsn, attempt, Instant.now().plus(backoff)));
        log.info("Rate-limited polling '{}' (guild {}) — retrying in {} (attempt {}).", rsn, guildId, backoff, attempt);
    }

    /** How many players are currently waiting out a rate-limit backoff. */
    public synchronized int size() {
        return queue.size();
    }

    /** Removes and returns every entry whose backoff has elapsed. */
    public synchronized List<Entry> drainDue() {
        Instant now = Instant.now();
        List<Entry> due = new ArrayList<>();
        queue.values().removeIf(entry -> {
            if (entry.nextRetryAt().isAfter(now)) return false;
            due.add(new Entry(entry.guildId(), entry.rsn(), entry.attempt()));
            return true;
        });
        return due;
    }

    private static Duration backoffFor(int attempt) {
        long minutes = BASE_BACKOFF.toMinutes() * (1L << Math.min(attempt - 1, 6)); // shift capped well below overflow
        return Duration.ofMinutes(Math.min(minutes, MAX_BACKOFF.toMinutes()));
    }

    private static String key(long guildId, String rsn) {
        return guildId + ":" + rsn.toLowerCase(Locale.ROOT);
    }
}
