package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
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
 * Two jobs, both scoped to every guild with a clan configured via {@code /configure}:
 * <p>
 * Once a day, at 00:00 UTC (the same moment RuneScape's own in-game day rolls over — the natural
 * point for "today's" clan activity to have settled), refreshes the roster itself (new/departed
 * members, rename detection — see {@link ClanSyncService#syncAndPoll}) and polls everyone on it.
 * <p>
 * Every {@link #ROSTER_POLL_INTERVAL} on top of that, {@link ClanSyncService#pollActiveRosterOnly}
 * re-polls the roster (without touching membership or running rename detection) — this is what keeps
 * an unlinked clan member's tracking feed (drops, levels, Citadel, etc.) as fresh as a linked
 * member's, instead of only ever updating once a day. Matches {@link RuneScapeStatsScheduler}'s own
 * cadence for a linked clan member's {@code /rs} profile, so "am I in the clan" doesn't change how
 * current your data looks.
 * <p>
 * Mirrors {@code SignupMaintenanceScheduler}'s JDA-ready pattern rather than
 * {@link RuneScapeStatsScheduler}'s plain timer, since resolving each tracked guild's {@link Guild}
 * (and knowing which guilds exist at all) needs JDA to be up first. The daily job uses
 * {@code scheduleAtFixedRate} specifically so it stays anchored to true midnight UTC over time,
 * instead of drifting later by however long each run took the day before; the roster-only job uses
 * {@code scheduleWithFixedDelay} since there's no particular moment it needs to land on. A 2-thread
 * pool (rather than the 1 this class used when it had only one job) keeps a slow daily sync from
 * delaying the hourly roster poll behind it.
 * <p>
 * Gated by the same {@link BotConfig#getRunescapeAutoPollEnabled()} flag as
 * {@link RuneScapeStatsScheduler}, so turning auto-polling off pauses both jobs here too.
 */
@BService
public class ClanSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClanSyncScheduler.class);

    private static final Duration DAILY_INTERVAL = Duration.ofDays(1);
    private static final Duration ROSTER_POLL_INTERVAL = Duration.ofHours(1);
    private static final Duration ROSTER_POLL_INITIAL_DELAY = Duration.ofMinutes(5);

    private final ClanSyncService clanSyncService;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2,
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "clan-sync-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    public ClanSyncScheduler(ClanSyncService clanSyncService, BotConfig botConfig) {
        this.clanSyncService = clanSyncService;
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED not set) — clan sync/roster polling will not run automatically.");
            return;
        }

        JDA jda = event.getJda();

        Duration initialDelay = durationUntilNextMidnightUtc();
        log.info("Clan sync scheduler starting: first daily sync in {}, then every {}; first roster-only poll in {}, then every {}.",
                initialDelay, DAILY_INTERVAL, ROSTER_POLL_INITIAL_DELAY, ROSTER_POLL_INTERVAL);
        executor.scheduleAtFixedRate(() -> runForAllGuilds(jda),
                initialDelay.toSeconds(), DAILY_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> pollAllRostersOnly(jda),
                ROSTER_POLL_INITIAL_DELAY.toSeconds(), ROSTER_POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private static Duration durationUntilNextMidnightUtc() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime nextMidnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT).atOffset(ZoneOffset.UTC);
        return Duration.between(now, nextMidnight);
    }

    private void runForAllGuilds(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            if (clanSyncService.getClanName(guild.getIdLong()) == null) continue; // nothing configured to sync

            try {
                var result = clanSyncService.syncAndPoll(guild);
                log.info("Scheduled clan sync for guild {}: {} in roster ({} new, {} departed), {}/{} updated successfully.",
                        guild.getIdLong(), result.rosterSize(), result.newMembers(), result.departedMembers(),
                        result.polled(), result.rosterSize());
            } catch (Exception e) {
                log.error("Scheduled clan sync failed for guild {}", guild.getIdLong(), e);
            }
        }
    }

    private void pollAllRostersOnly(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            long guildId = guild.getIdLong();
            if (clanSyncService.getClanName(guildId) == null) continue; // nothing configured to poll

            try {
                var result = clanSyncService.pollActiveRosterOnly(guildId);
                log.info("Hourly roster-only poll for guild {}: {}/{} clan member(s) updated successfully.",
                        guildId, result.polled(), result.polled() + result.pollFailed());
            } catch (Exception e) {
                log.error("Hourly roster-only poll failed for guild {}", guildId, e);
            }
        }
    }
}
