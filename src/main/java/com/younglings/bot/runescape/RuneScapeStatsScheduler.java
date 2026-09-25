package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Periodically re-polls every linked player's RuneMetrics profile and stores a snapshot — only when
 * something actually changed (see {@link RuneScapeStatsService}), so a quiet player doesn't rack up
 * needless database writes — so XP gains and level-ups can be tracked over time instead of only ever
 * seeing a live-fetched total.
 * <p>
 * Linked accounts split into two groups, each on its own timer: accounts whose RSN is currently in
 * their guild's tracked clan roster poll every {@link #CLAN_MEMBER_POLL_INTERVAL}, everyone else
 * (verified but not currently a clan member) every {@link #NON_CLAN_MEMBER_POLL_INTERVAL} — clan
 * members are the ones people actually check via {@code /rs} day to day, so they stay fresher; a
 * former or non-member's data matters less how current it is. {@link ClanSyncScheduler} is the
 * separate daily job that keeps the roster itself (who's a clan member at all) up to date.
 * <p>
 * <b>Disabled by setting {@code RUNESCAPE_AUTO_POLL_ENABLED=false}</b> — see
 * {@link BotConfig#getRunescapeAutoPollEnabled()}. While that's off, every poll happens on purpose
 * via the admin panel's "Update"/"Update All" buttons ({@code RsAdminCommand}) instead of on a timer.
 * <p>
 * Runs on a daemon thread (no shutdown hook needed) starting shortly after the bot boots, then on a
 * fixed delay — it does not depend on JDA/guild state at all, unlike {@link ClanSyncScheduler},
 * since polling an external HTTP API and checking a roster already stored in the database needs
 * neither.
 */
@BService
public class RuneScapeStatsScheduler {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeStatsScheduler.class);

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(2);
    private static final Duration CLAN_MEMBER_POLL_INTERVAL = Duration.ofHours(1);
    private static final Duration NON_CLAN_MEMBER_POLL_INTERVAL = Duration.ofHours(6);

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final ClanSyncService clanSyncService;
    private final Duration delayBetweenPlayers;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "runescape-stats-poller");
                thread.setDaemon(true);
                return thread;
            });

    public RuneScapeStatsScheduler(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                    ClanSyncService clanSyncService, BotConfig botConfig) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.clanSyncService = clanSyncService;
        this.delayBetweenPlayers = Duration.ofSeconds(botConfig.getRunescapePollDelaySeconds());

        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — use the admin panel's Update button instead.");
            return;
        }

        log.info("RuneScape stats poller starting: clan members every {}, non-clan members every {}, delayBetweenPlayers={}.",
                CLAN_MEMBER_POLL_INTERVAL, NON_CLAN_MEMBER_POLL_INTERVAL, delayBetweenPlayers);

        // Started directly in the constructor rather than a lifecycle-annotated method — this
        // framework's only verified post-construction hook is @BEventListener on JDA-related
        // events, which this scheduler has no actual need for (pure DB + HTTP polling, no Discord
        // calls), so there's nothing to gain from waiting on one.
        executor.scheduleWithFixedDelay(() -> pollGroup(true),
                INITIAL_DELAY.toSeconds(), CLAN_MEMBER_POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> pollGroup(false),
                INITIAL_DELAY.toSeconds(), NON_CLAN_MEMBER_POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** {@code clanMembersOnly} splits every linked account by whether its RSN is currently in its own guild's active clan roster — see the class doc for why the two groups poll at different rates. */
    private void pollGroup(boolean clanMembersOnly) {
        List<PlayerLink> links = linkService.getAllLinksAcrossGuilds();
        if (links.isEmpty()) return;

        Map<Long, Set<String>> rosterByGuild = new HashMap<>();
        List<PlayerLink> group = links.stream()
                .filter(link -> isClanMember(link, rosterByGuild) == clanMembersOnly)
                .toList();
        if (group.isEmpty()) return;

        String label = clanMembersOnly ? "clan member" : "non-clan member";
        log.info("RuneScape stats poll starting for {} {}(s).", group.size(), label);
        int succeeded = 0;

        for (PlayerLink link : group) {
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

        log.info("RuneScape stats poll finished for {}(s): {}/{} succeeded.", label, succeeded, group.size());
    }

    private boolean isClanMember(PlayerLink link, Map<Long, Set<String>> rosterByGuild) {
        Set<String> roster = rosterByGuild.computeIfAbsent(link.guildId(), guildId ->
                clanSyncService.getRoster(guildId, true).stream()
                        .map(member -> member.rsn().toLowerCase())
                        .collect(Collectors.toSet()));
        return roster.contains(link.rsn().toLowerCase());
    }
}
