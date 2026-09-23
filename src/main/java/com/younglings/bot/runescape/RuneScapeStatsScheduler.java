package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
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
 * <b>Tuning:</b> poll interval and per-player spacing are runtime settings, not code — see
 * {@link BotConfig#getRunescapePollIntervalMinutes()} and
 * {@link BotConfig#getRunescapePollDelaySeconds()} ({@code RUNESCAPE_POLL_INTERVAL_MINUTES} /
 * {@code RUNESCAPE_POLL_DELAY_SECONDS} env vars) — change either without a code change or
 * redeploy, just a bot restart to pick up the new value. Per-player spacing exists so a large
 * roster doesn't fire dozens of requests at once against an API with no documented rate limit but
 * no guarantee it tolerates bursts either.
 * <p>
 * Runs on a daemon thread (no shutdown hook needed) starting shortly after the bot boots, then on
 * a fixed delay — it does not depend on JDA/guild state at all, unlike {@code
 * SignupMaintenanceScheduler}, since polling an external HTTP API needs neither.
 */
@BService
public class RuneScapeStatsScheduler {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeStatsScheduler.class);

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(2);

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final Duration delayBetweenPlayers;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "runescape-stats-poller");
                thread.setDaemon(true);
                return thread;
            });

    public RuneScapeStatsScheduler(PlayerLinkService linkService, RuneScapeStatsService statsService, BotConfig botConfig) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.delayBetweenPlayers = Duration.ofSeconds(botConfig.getRunescapePollDelaySeconds());

        Duration pollInterval = Duration.ofMinutes(botConfig.getRunescapePollIntervalMinutes());
        log.info("RuneScape stats poller starting: interval={}, delayBetweenPlayers={}", pollInterval, delayBetweenPlayers);

        // Started directly in the constructor rather than a lifecycle-annotated method — this
        // framework's only verified post-construction hook is @BEventListener on JDA-related
        // events, which this scheduler has no actual need for (pure DB + HTTP polling, no Discord
        // calls), so there's nothing to gain from waiting on one.
        executor.scheduleWithFixedDelay(this::pollAll,
                INITIAL_DELAY.toSeconds(), pollInterval.toSeconds(), TimeUnit.SECONDS);
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
                Thread.sleep(delayBetweenPlayers.toMillis());
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
