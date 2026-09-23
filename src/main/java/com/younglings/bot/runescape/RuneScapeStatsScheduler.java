package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Periodically re-polls every linked player's RuneMetrics profile and stores a snapshot, so XP
 * gains and level-ups can be tracked over time instead of only ever seeing a live-fetched total.
 * <p>
 * <b>Tuning:</b> everything below is a plain constant, not a runtime setting — ask for a change
 * (rate, jitter, batch size) and it's a one-line edit, no admin UI needed for this yet:
 * <ul>
 *     <li>{@link #POLL_INTERVAL} — how often the full player list gets re-polled</li>
 *     <li>{@link #DELAY_BETWEEN_PLAYERS} — spacing between individual requests, so a large roster
 *     doesn't fire dozens of requests at once against an API with no documented rate limit but no
 *     guarantee it tolerates bursts either</li>
 * </ul>
 * Runs on a daemon thread (no shutdown hook needed) starting shortly after the bot boots, then on
 * a fixed delay — it does not depend on JDA/guild state at all, unlike {@code
 * SignupMaintenanceScheduler}, since polling an external HTTP API needs neither.
 */
@BService
public class RuneScapeStatsScheduler {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeStatsScheduler.class);

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(2);
    private static final Duration POLL_INTERVAL = Duration.ofHours(6);
    private static final Duration DELAY_BETWEEN_PLAYERS = Duration.ofSeconds(2);

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "runescape-stats-poller");
                thread.setDaemon(true);
                return thread;
            });

    public RuneScapeStatsScheduler(PlayerLinkService linkService, RuneScapeStatsService statsService) {
        this.linkService = linkService;
        this.statsService = statsService;
        // Started directly in the constructor rather than a lifecycle-annotated method — this
        // framework's only verified post-construction hook is @BEventListener on JDA-related
        // events, which this scheduler has no actual need for (pure DB + HTTP polling, no Discord
        // calls), so there's nothing to gain from waiting on one.
        executor.scheduleWithFixedDelay(this::pollAll,
                INITIAL_DELAY.toSeconds(), POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void pollAll() {
        List<PlayerLink> links = linkService.getAllLinksAcrossGuilds();
        if (links.isEmpty()) return;

        log.info("RuneScape stats poll starting for {} linked player(s).", links.size());
        int succeeded = 0;

        for (PlayerLink link : links) {
            try {
                boolean ok = statsService.pollAndSnapshot(link.guildId(), link.rsn()).isPresent();
                if (ok) succeeded++;
                Thread.sleep(DELAY_BETWEEN_PLAYERS.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Failed to poll stats for '{}'", link.rsn(), e);
            }
        }

        log.info("RuneScape stats poll finished: {}/{} succeeded.", succeeded, links.size());
    }
}
