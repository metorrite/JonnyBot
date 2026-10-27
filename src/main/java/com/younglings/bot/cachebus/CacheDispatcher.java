package com.younglings.bot.cachebus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Hands each signal to the caches of its area. One cache that fails never stops the others, or the listener. */
public final class CacheDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CacheDispatcher.class);

    private final Map<String, List<CacheInvalidator>> byArea = new TreeMap<>();

    public CacheDispatcher(Collection<? extends CacheInvalidator> invalidators) {
        for (CacheInvalidator invalidator : invalidators) {
            String area = invalidator.cacheArea();
            if (!CacheSignal.validArea(area)) throw new IllegalArgumentException(invalidator.getClass().getName() + " has a bad cache area: " + area);
            byArea.computeIfAbsent(area, a -> new ArrayList<>()).add(invalidator);
        }
    }

    /** The areas some cache answers to, sorted. */
    public List<String> areas() {
        return List.copyOf(byArea.keySet());
    }

    /** Evicts what {@code payload} names; false when it isn't a signal or no cache has its area. */
    public boolean dispatch(String payload) {
        Optional<CacheSignal> signal = CacheSignal.parse(payload);
        if (signal.isEmpty()) {
            log.debug("Ignored a cache signal that isn't one: {}", payload);
            return false;
        }
        List<CacheInvalidator> targets = byArea.get(signal.get().area());
        if (targets == null) return false;
        for (CacheInvalidator target : targets) {
            try {
                target.evictCached(signal.get().guildId());
            } catch (RuntimeException e) {
                log.warn("{} failed to drop guild {} from its cache", target.getClass().getSimpleName(), signal.get().guildId(), e);
            }
        }
        return true;
    }

    /** Drops every cache's entries (after a reconnect, when signals may have been missed). */
    public void evictAll() {
        for (List<CacheInvalidator> targets : byArea.values()) {
            for (CacheInvalidator target : targets) {
                try {
                    target.evictAllCached();
                } catch (RuntimeException e) {
                    log.warn("{} failed to clear its cache", target.getClass().getSimpleName(), e);
                }
            }
        }
    }
}
