package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.tracking.ClanPointsService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Once a day, at 00:10 UTC — 10 minutes after {@link ClanSyncScheduler}'s own 00:00 UTC run, so
 * every member's {@code clan_rank} is already freshly synced before the promotion check reads it —
 * runs {@link ClanPointsService#runDailyPointsAndPromotionCheck}, which awards points and sends the
 * Clan Report for every guild with a clan configured.
 * <p>
 * Same JDA-ready + {@code scheduleAtFixedRate} pattern as {@link ClanSyncScheduler}/{@code WeeklyDigestScheduler},
 * and same {@link BotConfig#getRunescapeAutoPollEnabled()} gate as every other scheduler here.
 */
@BService
public class ClanPointsScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClanPointsScheduler.class);

    private static final LocalTime RUN_TIME_UTC = LocalTime.of(0, 10);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final ClanPointsService clanPointsService;
    private final ClanSyncService clanSyncService;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "clan-points-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    public ClanPointsScheduler(ClanPointsService clanPointsService, ClanSyncService clanSyncService, BotConfig botConfig) {
        this.clanPointsService = clanPointsService;
        this.clanSyncService = clanSyncService;
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED not set) — the clan points/promotion check will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration initialDelay = durationUntilNext0010Utc();
        log.info("Clan points scheduler starting: first run in {}, then every {}.", initialDelay, INTERVAL);
        executor.scheduleAtFixedRate(() -> runForAllGuilds(jda), initialDelay.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private static Duration durationUntilNext0010Utc() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.toLocalDate().atTime(RUN_TIME_UTC).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Duration.between(now, candidate);
    }

    private void runForAllGuilds(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            if (clanSyncService.getClanName(guild.getIdLong()) == null) continue; // nothing configured to check

            try {
                clanPointsService.runDailyPointsAndPromotionCheck(guild);
                log.info("Clan points/promotion check finished for guild {}.", guild.getIdLong());
            } catch (Exception e) {
                log.error("Clan points/promotion check failed for guild {}", guild.getIdLong(), e);
            }
        }
    }
}
