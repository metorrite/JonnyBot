package com.younglings.bot.internal;

import com.younglings.bot.discord.DiscordLookups;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A cache for answers that are slow to work out (they ask Discord, or read a lot), so a page never waits for them twice:
 * <ul>
 *   <li>an answer younger than {@code freshMs} is served as is;</li>
 *   <li>an older one (up to {@code maxStaleMs}) is served at once and refreshed in the background, one refresh per key at a time;</li>
 *   <li>with nothing to serve, the answer is worked out on a worker thread and waited for at most {@code budgetMs}. If it isn't ready by then
 *       the caller gets {@link DiscordLookups.Slow} (a 503 "try again"), and the work carries on and fills the cache for the next visit.</li>
 * </ul>
 * {@link #invalidate} drops matching answers and also marks any refresh already under way as out of date, so a write is never followed by an
 * old answer being stored. A loader that fails is never cached; the old answer, if there is one, keeps being served.
 */
final class StaleCache {
    private record Entry(Object value, long loadedAt) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();
    private final LongSupplier clock;
    private final Executor executor;

    StaleCache(LongSupplier clock, Executor executor) {
        this.clock = clock;
        this.executor = executor;
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, long freshMs, long maxStaleMs, long budgetMs, Supplier<T> loader) {
        Entry entry = entries.get(key);
        long age = entry == null ? Long.MAX_VALUE : clock.getAsLong() - entry.loadedAt();
        if (entry != null && age < freshMs) return (T) entry.value();
        if (entry != null && age < maxStaleMs) {
            try {
                refresh(key, loader);
            } catch (DiscordLookups.Slow busy) {
                // no worker free right now; the old answer is still good enough
            }
            return (T) entry.value();
        }
        CompletableFuture<Object> future = refresh(key, loader);
        try {
            return (T) future.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new DiscordLookups.Slow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscordLookups.Slow();
        } catch (ExecutionException e) {
            if (entry != null) return (T) entry.value(); // stale beats broken
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    /** Starts (or joins) the one load for {@code key}. */
    private CompletableFuture<Object> refresh(String key, Supplier<?> loader) {
        CompletableFuture<Object> existing = inFlight.get(key);
        if (existing != null) return existing;
        CompletableFuture<Object> fresh = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(key, fresh);
        if (existing != null) return existing;

        long startedIn = epoch.get();
        try {
            executor.execute(() -> {
                try {
                    Object value = loader.get();
                    if (value != null && startedIn == epoch.get()) entries.put(key, new Entry(value, clock.getAsLong()));
                    inFlight.remove(key, fresh);
                    fresh.complete(value);
                } catch (Throwable t) {
                    inFlight.remove(key, fresh);
                    fresh.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException full) {
            inFlight.remove(key, fresh);
            fresh.completeExceptionally(new DiscordLookups.Slow());
        }
        return fresh;
    }

    /** The task given to {@link #within} didn't finish in time; it carries on in the background. */
    static final class Late extends RuntimeException {
        Late() {
            super("Still working", null, false, false);
        }
    }

    /** Runs {@code task} on a worker and waits at most {@code budgetMs}; the task carries on past that (and {@code whenDone} runs when it ends). Throws {@link Late} if it didn't finish in time. */
    @SuppressWarnings("unchecked")
    <T, E extends Exception> T within(long budgetMs, Work<T, E> task, Runnable whenDone) throws E {
        CompletableFuture<Object> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    whenDone.run();
                }
            });
        } catch (RejectedExecutionException full) {
            throw new DiscordLookups.Slow();
        }
        try {
            return (T) future.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Late();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Late();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw (E) cause; // the task's own checked exception, as declared by E
        }
    }

    /** A task that may throw a checked exception of type {@code E}. */
    @FunctionalInterface
    interface Work<T, E extends Exception> {
        T run() throws E;
    }

    /** Forgets answers whose key starts with {@code prefix}, and makes loads already under way discard what they find. */
    void invalidate(String prefix) {
        epoch.incrementAndGet();
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    void clear() {
        epoch.incrementAndGet();
        entries.clear();
    }
}
