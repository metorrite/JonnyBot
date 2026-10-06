package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.function.Supplier;

/**
 * The shared cache behind the website's data routes. One instance, so a write anywhere (a vote, a signup, a
 * privacy toggle, an admin action) can drop exactly the answers it made stale instead of waiting out a timer.
 * See {@link TtlCache} for how entries load.
 */
@BService
public class SiteCache {
    private final TtlCache cache = new TtlCache();

    <T> T get(String key, long ttlMillis, Supplier<T> loader) {
        return cache.get(key, ttlMillis, loader);
    }

    /** Forgets every cached answer. */
    public void clear() {
        cache.clear();
    }

    /** Forgets the cached answers for routes starting with {@code routePrefix} (e.g. {@code "polls"}). */
    public void invalidate(String routePrefix) {
        cache.invalidate(routePrefix);
    }
}
