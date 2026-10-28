package com.runeclans.bot.internal;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

/**
 * Subscribes {@link AdminReads}' cache of dashboard pages to {@link CacheBus} (docs/SCALING-STATE.md M10): a change saved on another process
 * drops this server's pages here too, instead of them being served for up to their TTL. {@link AdminReads#forget} sends the signal.
 */
@BService
public class AdminReadsCache implements CacheInvalidator {
    static final String CACHE_AREA = "admin-reads";

    public AdminReadsCache() {
    }

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        AdminReads.forgetSignalled(guildId);
    }

    @Override
    public void evictAllCached() {
        AdminReads.clearAll();
    }
}
