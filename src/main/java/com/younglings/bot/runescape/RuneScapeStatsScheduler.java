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
 * Periodically re-polls a linked player's RuneMetrics profile and stores a snapshot — only when
 * something actually changed (see {@link RuneScapeStatsService}), so a quiet player doesn't rack up
 * needless database writes — so XP gains and level-ups can be tracked over time instead of only ever
 * seeing a live-fetched total.
 * <p>
 * Only covers linked accounts whose RSN is <em>not</em> currently in their guild's tracked clan
 * roster (verified, but not currently a member) — a current clan member, linked or not, is already
 * kept fresh by {@link ClanSyncScheduler}'s own hourly roster-only poll, which covers the entire
 * roster in one pass and is what actually drives the tracking feed for an unlinked member. Polling
 * that same account again here on top of that would just be wasted API calls, so this class shrank to
 * the one group {@link ClanSyncScheduler} doesn't reach: a former or non-member's data matters less
 * how current it is, hence the longer {@link #NON_CLAN_MEMBER_POLL_INTERVAL}.
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

        log.info("RuneScape stats poller starting: non-clan-member linked accounts every {}, delayBetweenPlayers={}.",
                NON_CLAN_MEMBER_POLL_INTERVAL, delayBetweenPlayers);

        // Started directly in the constructor rather than a lifecycle-annotated method — this
        // framework's only verified post-construction hook is @BEventListener on JDA-related
        // events, which this scheduler has no actual need for (pure DB + HTTP polling, no Discord
        // calls), so there's nothing to gain from waiting on one.
        executor.scheduleWithFixedDelay(this::pollNonClanMembers,
                INITIAL_DELAY.toSeconds(), NON_CLAN_MEMBER_POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Every linked account whose RSN is *not* currently in its own guild's active clan roster — see the class doc for why current clan members aren't polled here at all anymore. */
    private void pollNonClanMembers() {
        List<PlayerLink> links = linkService.getAllLinksAcrossGuilds();
        if (links.isEmpty()) return;

        Map<Long, Set<String>> rosterByGuild = new HashMap<>();
        List<PlayerLink> group = links.stream()
                .filter(link -> !isClanMember(link, rosterByGuild))
                .toList();
        if (group.isEmpty()) return;

        log.info("RuneScape stats poll starting for {} non-clan-member(s).", group.size());
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

        log.info("RuneScape stats poll finished for non-clan-member(s): {}/{} succeeded.", succeeded, group.size());
    }

    private boolean isClanMember(PlayerLink link, Map<Long, Set<String>> rosterByGuild) {
        Set<String> roster = rosterByGuild.computeIfAbsent(link.guildId(), guildId ->
                clanSyncService.getRoster(guildId, true).stream()
                        .map(member -> member.rsn().toLowerCase())
                        .collect(Collectors.toSet()));
        return roster.contains(link.rsn().toLowerCase());
    }
}
