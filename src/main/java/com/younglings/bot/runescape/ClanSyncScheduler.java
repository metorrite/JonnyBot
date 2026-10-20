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
 * Once a day, at 00:00 UTC (the same moment RuneScape's own in-game day rolls over — the natural
 * point for "today's" clan activity to have settled), refreshes every configured guild's clan
 * roster (new/departed members, rename detection — see {@link ClanSyncService#syncAndPoll}) and
 * polls everyone currently on it.
 * <p>
 * This is purely about the roster itself — who's a member at all. Keeping every member's own data
 * (and the tracking feed it drives) fresh the rest of the day is {@link RosterPollScheduler}'s job,
 * spread evenly across a shorter window instead of bursting once daily.
 * <p>
 * Mirrors {@code SignupMaintenanceScheduler}'s JDA-ready pattern rather than a plain timer, since
 * resolving each tracked guild's {@link Guild} object (to pass to {@code syncAndPoll}) needs JDA to be
 * up first. Uses {@code scheduleAtFixedRate} rather than {@code scheduleWithFixedDelay} specifically so
 * the daily run stays anchored to true midnight UTC over time, instead of drifting later by however
 * long each run took the day before.
 * <p>
 * Gated by the same {@link BotConfig#getRunescapeAutoPollEnabled()} flag as {@link RosterPollScheduler},
 * so turning auto-polling off pauses this too.
 */
@BService
public class ClanSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClanSyncScheduler.class);

    private static final Duration INTERVAL = Duration.ofDays(1);

    private final ClanSyncService clanSyncService;
    private final BotConfig botConfig;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
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
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED not set) — daily clan sync will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration initialDelay = durationUntilNextMidnightUtc();
        log.info("Clan sync scheduler starting: first run in {}, then every {}.", initialDelay, INTERVAL);
        executor.scheduleAtFixedRate(() -> runForAllGuilds(jda),
                initialDelay.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private static Duration durationUntilNextMidnightUtc() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime nextMidnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT).atOffset(ZoneOffset.UTC);
        return Duration.between(now, nextMidnight);
    }

    private void runForAllGuilds(JDA jda) {
        for (var group : groupByClan(jda.getGuilds(), clanSyncService::getClanName).values()) {
            try {
                var results = clanSyncService.syncAndPollGroup(group);
                results.forEach((guildId, result) -> log.info("Scheduled clan sync for guild {}: {} in roster ({} new, {} departed), {}/{} updated successfully.",
                        guildId, result.rosterSize(), result.newMembers(), result.departedMembers(), result.polled(), result.rosterSize()));
            } catch (Exception e) {
                log.error("Scheduled clan sync failed for the clan of guild {}", group.get(0).getIdLong(), e);
            }
        }
    }

    /**
     * The servers that have set up a clan, grouped by clan (by {@link ClanSyncService#clanKey}), so a clan that is set up in two servers is fetched and
     * polled once. A server with no clan is left out.
     */
    static java.util.Map<String, java.util.List<Guild>> groupByClan(java.util.Collection<Guild> guilds, java.util.function.Function<Long, String> clanNameOf) {
        java.util.Map<String, java.util.List<Guild>> groups = new java.util.LinkedHashMap<>();
        for (Guild guild : guilds) {
            String clan = clanNameOf.apply(guild.getIdLong());
            if (clan == null) continue; // nothing configured to sync
            groups.computeIfAbsent(ClanSyncService.clanKey(clan), k -> new java.util.ArrayList<>()).add(guild);
        }
        return groups;
    }
}
