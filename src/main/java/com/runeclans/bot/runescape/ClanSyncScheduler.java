package com.runeclans.bot.runescape;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.clansite.BotlessSiteRepository;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.util.BotScheduler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
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
    private final BotlessSiteRepository botless;
    private final ScheduledExecutorService executor = BotScheduler.lane("clan-sync-scheduler");

    public ClanSyncScheduler(ClanSyncService clanSyncService, BotConfig botConfig, BotlessSiteRepository botless) {
        this.clanSyncService = clanSyncService;
        this.botConfig = botConfig;
        this.botless = botless;
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
        List<Long> botlessIds = botlessWithoutBot(jda);
        for (var group : groupByClan(Shards.guilds(jda), botlessIds, clanSyncService::getClanName).values()) {
            try {
                var results = clanSyncService.syncAndPollGroup(group.guilds(), group.botlessGuildIds(), true);
                results.forEach((guildId, result) -> log.info("Scheduled clan sync for guild {}: {} in roster ({} new, {} departed), {}/{} updated successfully.",
                        guildId, result.rosterSize(), result.newMembers(), result.departedMembers(), result.polled(), result.rosterSize()));
            } catch (Exception e) {
                log.error("Scheduled clan sync failed for the clan '{}'", group.clanKey(), e);
            }
        }
    }

    /** The bot-less sites' servers the bot still isn't in (one it has joined since is synced as a server of its own). */
    private List<Long> botlessWithoutBot(JDA jda) {
        try {
            return botless.sorted().stream().filter(id -> Shards.guild(jda, id) == null).toList();
        } catch (RuntimeException e) {
            log.warn("Couldn't read the bot-less clan websites; syncing the bot's own servers only ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Syncs one bot-less site's clan now, in the background (its admin just set the clan name), instead of at the next midnight. Does nothing
     * while auto-polling is off, like the daily run.
     */
    public void syncSoon(long guildId) {
        if (!botConfig.getRunescapeAutoPollEnabled() || clanSyncService.getClanName(guildId) == null) return;
        executor.execute(() -> {
            try {
                clanSyncService.syncAndPollGroup(List.of(), List.of(guildId));
            } catch (Exception e) {
                log.error("Clan sync for the bot-less site of guild {} failed", guildId, e);
            }
        });
    }

    /** One clan's servers: those the bot is in, and bot-less sites (by server id) that show the same clan. */
    record ClanGroup(String clanKey, List<Guild> guilds, List<Long> botlessGuildIds) {}

    /**
     * {@link #groupByClan(java.util.Collection, java.util.function.Function)} with bot-less sites too: each clan once, with the bot's servers
     * and the bot-less sites that show it, so a clan shared by both is still fetched and polled once.
     */
    static java.util.Map<String, ClanGroup> groupByClan(java.util.Collection<Guild> guilds, java.util.Collection<Long> botlessGuildIds,
                                                        java.util.function.Function<Long, String> clanNameOf) {
        java.util.Map<String, ClanGroup> groups = new java.util.LinkedHashMap<>();
        groupByClan(guilds, clanNameOf).forEach((key, list) -> groups.put(key, new ClanGroup(key, list, new ArrayList<>())));
        for (long guildId : botlessGuildIds) {
            String clan = clanNameOf.apply(guildId);
            if (clan == null) continue;
            String key = ClanSyncService.clanKey(clan);
            groups.computeIfAbsent(key, k -> new ClanGroup(k, new ArrayList<>(), new ArrayList<>())).botlessGuildIds().add(guildId);
        }
        return groups;
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
