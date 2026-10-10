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
 * Drains {@link SlowPollQueue} once a minute, retrying whichever rate-limited RSNs have finished their
 * backoff — a re-poll that still comes back {@link ProfileResult.RateLimited} re-enqueues itself with
 * the next backoff step automatically, since it goes through the exact same
 * {@link RuneScapeStatsService#pollAndSnapshotResult} choke point every other poll does. Paced gently
 * even within one drain pass (several due entries at once shouldn't turn into their own burst).
 * <p>
 * Doesn't depend on JDA — same reasoning as the old {@code RuneScapeStatsScheduler}: this is pure
 * DB/HTTP work, and {@link RuneScapeStatsService#pollAndSnapshotResult}'s own tracking-dispatch step
 * already no-ops gracefully if JDA isn't ready yet.
 */
@BService
public class SlowPollScheduler {
    private static final Logger log = LoggerFactory.getLogger(SlowPollScheduler.class);

    private static final Duration DRAIN_INTERVAL = Duration.ofMinutes(1);
    private static final Duration BETWEEN_RETRIES = Duration.ofSeconds(5);

    private final SlowPollQueue queue;
    private final RuneScapeStatsService statsService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "slow-poll-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    public SlowPollScheduler(SlowPollQueue queue, RuneScapeStatsService statsService, BotConfig botConfig) {
        this.queue = queue;
        this.statsService = statsService;

        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — rate-limited retries will not run automatically.");
            return;
        }

        log.info("Slow-poll scheduler starting: checking for rate-limited retries every {}.", DRAIN_INTERVAL);
        executor.scheduleWithFixedDelay(this::drain, DRAIN_INTERVAL.toSeconds(), DRAIN_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void drain() {
        List<SlowPollQueue.Entry> due = queue.drainDue();
        if (due.isEmpty()) return;

        log.info("Slow-poll queue: retrying {} previously rate-limited player(s).", due.size());
        for (SlowPollQueue.Entry entry : due) {
            try {
                statsService.pollAndSnapshotResult(entry.rsn());
                Thread.sleep(BETWEEN_RETRIES.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Slow-poll retry failed for '{}'", entry.rsn(), e);
            }
        }
    }
}
