package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.tracking.WeeklyDigestService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Every Wednesday at 01:00 UTC — one hour after the Clan Citadel's own weekly reset — sends the weekly
 * joins/leaves and Citadel visits/caps digests (see {@link WeeklyDigestService}) for every guild with a
 * clan configured, covering the window from the *previous* Wednesday 00:01 UTC up to that same day's
 * 00:00 UTC (i.e. the week that just finished, right before the reset that starts the next one).
 * <p>
 * Same JDA-ready + {@code scheduleAtFixedRate} pattern as {@link ClanSyncScheduler}, and same
 * {@link BotConfig#getRunescapeAutoPollEnabled()} gate as every other scheduler here.
 */
@BService
public class WeeklyDigestScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestScheduler.class);

    private static final LocalTime RUN_TIME_UTC = LocalTime.of(1, 0);
    private static final Duration INTERVAL = Duration.ofDays(7);

    private final WeeklyDigestService weeklyDigestService;
    private final ClanSyncService clanSyncService;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "weekly-digest-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    public WeeklyDigestScheduler(WeeklyDigestService weeklyDigestService, ClanSyncService clanSyncService, BotConfig botConfig) {
        this.weeklyDigestService = weeklyDigestService;
        this.clanSyncService = clanSyncService;
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — the weekly digest will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration initialDelay = durationUntilNextWednesday0100Utc();
        log.info("Weekly digest scheduler starting: first run in {}, then every {}.", initialDelay, INTERVAL);
        executor.scheduleAtFixedRate(() -> runForAllGuilds(jda), initialDelay.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private static Duration durationUntilNextWednesday0100Utc() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY)).with(RUN_TIME_UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
        return Duration.between(now, candidate);
    }

    private void runForAllGuilds(JDA jda) {
        // windowEnd is "today" at 00:00 UTC — this job always runs at 01:00 UTC on the Wednesday the
        // window ends, so deriving it from "now" rather than hardcoding Wednesday keeps this correct
        // even if a run fires a little late.
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime windowStart = windowEnd.minusDays(7).plusMinutes(1);

        for (Guild guild : jda.getGuilds()) {
            if (clanSyncService.getClanName(guild.getIdLong()) == null) continue; // nothing configured to report on

            try {
                weeklyDigestService.sendWeeklyDigests(guild, windowStart, windowEnd);
                log.info("Weekly digest sent for guild {} covering {} to {}.", guild.getIdLong(), windowStart, windowEnd);
            } catch (Exception e) {
                log.error("Weekly digest failed for guild {}", guild.getIdLong(), e);
            }
        }
    }
}
