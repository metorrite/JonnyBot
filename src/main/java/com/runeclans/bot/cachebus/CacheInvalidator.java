package com.runeclans.bot.cachebus;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;

/**
 * A cache of database rows that another process may change (docs/SCALING-STATE.md M10). Implement it on the {@code @BService} that holds the
 * cache and {@link CacheBus} picks it up with no edit anywhere else: when another process sends {@code area:guildId} on the {@code bot_cache}
 * channel, {@link #evictCached} drops that server's entries so the next read loads them from Postgres again.
 * <p>
 * Both methods only drop entries: they must never write, and never call {@link CacheBus#changed} (that would echo the signal back). They run on
 * the listener's lane, so they must be quick and must not wait on Discord. Dropping an entry that is not there is fine; so is dropping one
 * this process just wrote (the writer's own signal comes back to it too, and the reload reads what it wrote).
 */
@InterfacedService(acceptMultiple = true)
public interface CacheInvalidator {
    /** The area this cache answers to: lower case letters, digits and dashes, as in {@link CacheSignal}. */
    String cacheArea();

    /** Drops what is cached for this server. */
    void evictCached(long guildId);

    /** Drops everything cached (the listener reconnected, so signals may have been missed while it was away). */
    void evictAllCached();
}
