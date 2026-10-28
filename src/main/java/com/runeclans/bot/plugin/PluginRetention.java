package com.runeclans.bot.plugin;

import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Raw plugin events are kept for 180 days (set {@code PLUGIN_EVENT_RETENTION_DAYS} to change it, 0 to keep them) and then deleted, so
 * JonnyBot does not hold a permanent log of what people did in the game. Checked once a day. RuneSync's diagnostics snapshots expire after
 * 14 days and are removed by the same daily check, whatever the event setting.
 */
@BService
public class PluginRetention {
    private static final Logger log = LoggerFactory.getLogger(PluginRetention.class);
    static final int DEFAULT_DAYS = 180;

    private final PluginRepository repository;
    private final RuneSyncRepository runeSync;
    private final ScheduledExecutorService executor = BotScheduler.lane("plugin-retention");

    public PluginRetention(PluginRepository repository, RuneSyncRepository runeSync) {
        this.repository = repository;
        this.runeSync = runeSync;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(this::purgeDiagnostics, 2, 24 * 60, TimeUnit.MINUTES);
        int days = retentionDays(System.getenv("PLUGIN_EVENT_RETENTION_DAYS"));
        if (days <= 0) {
            log.info("Plugin event retention is off (PLUGIN_EVENT_RETENTION_DAYS=0): reported events are kept.");
            return;
        }
        executor.scheduleWithFixedDelay(() -> purge(days), 1, 24, TimeUnit.HOURS);
    }

    /** The configured number of days: the default when unset or unreadable, never negative. */
    static int retentionDays(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_DAYS;
        try {
            return Math.max(0, Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
    }

    void purge(int days) {
        try {
            int removed = repository.purgeEventsBefore(Instant.now().minus(Duration.ofDays(days)));
            if (removed > 0) log.info("Removed {} plugin event(s) older than {} days", removed, days);
        } catch (Exception e) {
            log.error("Plugin event retention failed", e);
        }
    }

    void purgeDiagnostics() {
        try {
            int removed = runeSync.purgeExpiredDiagnostics(Instant.now());
            if (removed > 0) log.info("Removed {} expired RuneSync diagnostics snapshot(s)", removed);
        } catch (Exception e) {
            log.error("RuneSync diagnostics retention failed", e);
        }
    }
}
