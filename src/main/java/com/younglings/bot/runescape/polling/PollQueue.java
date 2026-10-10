package com.younglings.bot.runescape.polling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * The waiting line: at most one entry per player, served best priority first.
 * <p>
 * Entries start in a {@code waiting} set ordered by the time they become eligible (a spread tier holds its players
 * back so they trickle in across a window), and move to {@code ready} once that time passes. {@code ready} is ordered
 * by priority, then by when the entry became eligible, so an interactive request jumps everything else and equals are
 * served in arrival order. Splitting them means an entry that is not yet eligible never blocks one that is.
 * <p>
 * One entry per player is what stops the same player being polled twice for two requests: a second request for a name
 * already in line merges into the existing entry (best priority, strictest freshness, earliest eligibility) and shares
 * its outcome. Not thread-safe; {@link PollEngine} guards it.
 */
final class PollQueue {
    /** A queued poll. Mutate only through {@link PollQueue}, which keeps the sorted sets consistent. */
    static final class Item {
        final String key;
        final String rsn;
        final long seq;
        PollPriority priority;
        long maxAgeMs;
        long notBeforeMs;
        String source;
        int attempts;
        /** When the freshness lookup last ran for this entry, so waiting for a token doesn't repeat it. 0 = not yet. */
        long freshCheckedAtMs;
        boolean ready;
        final List<CompletableFuture<PollOutcome>> waiters = new ArrayList<>();

        private Item(String key, String rsn, long seq) {
            this.key = key;
            this.rsn = rsn;
            this.seq = seq;
        }
    }

    private final TreeSet<Item> waiting = new TreeSet<>(Comparator.<Item>comparingLong(i -> i.notBeforeMs).thenComparingLong(i -> i.seq));
    private final TreeSet<Item> ready = new TreeSet<>(Comparator.<Item, PollPriority>comparing(i -> i.priority)
            .thenComparingLong(i -> i.notBeforeMs).thenComparingLong(i -> i.seq));
    private final Map<String, Item> byKey = new HashMap<>();
    private long nextSeq;

    static String key(String rsn) {
        return rsn.trim().toLowerCase(Locale.ROOT);
    }

    Item get(String rsn) {
        return byKey.get(key(rsn));
    }

    int size() {
        return byKey.size();
    }

    /** Adds a new entry. The caller checked {@link #get} first: a name already in line is merged, not added again. */
    Item add(PollRequest request, long nowMs) {
        Item item = new Item(key(request.rsn()), request.rsn().trim(), nextSeq++);
        item.priority = request.priority();
        item.maxAgeMs = request.maxAge().toMillis();
        item.notBeforeMs = nowMs + request.delay().toMillis();
        item.source = request.source();
        byKey.put(item.key, item);
        place(item, nowMs);
        return item;
    }

    /** Folds a second request for the same player into its entry. Returns whether it moved the entry up the line. */
    boolean merge(Item item, PollRequest request, long nowMs) {
        PollPriority before = item.priority;
        long notBeforeBefore = item.notBeforeMs;
        detach(item);
        if (request.priority().beats(item.priority)) {
            item.priority = request.priority();
            item.source = request.source();
        }
        item.maxAgeMs = Math.min(item.maxAgeMs, request.maxAge().toMillis());
        item.notBeforeMs = Math.min(item.notBeforeMs, nowMs + request.delay().toMillis());
        item.freshCheckedAtMs = 0;
        place(item, nowMs);
        return item.priority != before || item.notBeforeMs < notBeforeBefore;
    }

    /** Puts an entry that was taken out for polling back in, eligible again at {@code notBeforeMs} (a rate-limit retry). */
    void requeue(Item item, long notBeforeMs, long nowMs) {
        item.notBeforeMs = notBeforeMs;
        item.freshCheckedAtMs = 0;
        byKey.put(item.key, item);
        place(item, nowMs);
    }

    /** Takes an entry out of the line. False if it is no longer the one queued under its name (it was merged or removed meanwhile). */
    boolean remove(Item item) {
        if (byKey.get(item.key) != item) return false;
        detach(item);
        byKey.remove(item.key);
        return true;
    }

    /** Makes every entry whose time has come eligible. */
    void promoteDue(long nowMs) {
        while (!waiting.isEmpty() && waiting.first().notBeforeMs <= nowMs) {
            Item item = waiting.pollFirst();
            item.ready = true;
            ready.add(item);
        }
    }

    /** The entry that should be polled next, without taking it out. */
    Item peekReady() {
        return ready.isEmpty() ? null : ready.first();
    }

    /** When the earliest not-yet-eligible entry becomes eligible, if any. */
    OptionalLong nextWakeup() {
        return waiting.isEmpty() ? OptionalLong.empty() : OptionalLong.of(waiting.first().notBeforeMs);
    }

    /** Every waiting entry, in no particular order. */
    List<Item> all() {
        return new ArrayList<>(byKey.values());
    }

    Map<PollPriority, Integer> countsByPriority() {
        Map<PollPriority, Integer> counts = new EnumMap<>(PollPriority.class);
        for (PollPriority priority : PollPriority.values()) counts.put(priority, 0);
        for (Item item : byKey.values()) counts.merge(item.priority, 1, Integer::sum);
        return counts;
    }

    private void place(Item item, long nowMs) {
        if (item.notBeforeMs <= nowMs) {
            item.ready = true;
            ready.add(item);
        } else {
            item.ready = false;
            waiting.add(item);
        }
    }

    private void detach(Item item) {
        (item.ready ? ready : waiting).remove(item);
    }
}
