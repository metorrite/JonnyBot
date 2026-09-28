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
 * Keeps every tracked player's data (and the tracking feed it drives) fresh, spread evenly across a
 * target window instead of bursting everyone at once and then going quiet until the next cycle — see
 * {@link ClanSyncScheduler} for the separate, once-a-day concern of keeping the roster itself (who's a
 * member at all) up to date.
 * <p>
 * Two independent groups, each with its own window:
 * <ul>
 *   <li>Every guild's active clan roster (linked or not) — one independently-scheduled job per guild,
 *       each spreading that guild's own members across {@link #CLAN_MEMBER_WINDOW}, so one large
 *       clan's roster size can't slow down another guild's polling.</li>
 *   <li>Every linked account that ISN'T currently in its own guild's clan roster (verified, but not a
 *       member) — one combined pool across every guild, spread across {@link #NON_CLAN_MEMBER_WINDOW}
 *       and anchored so a cycle's last poll lands right before {@link #NON_CLAN_MEMBER_ANCHOR_UTC} UTC,
 *       30 minutes ahead of RuneScape's own daily reset. A former/non-member's data matters less how
 *       fresh it is, but landing right before the reset keeps that day's numbers as current as
 *       reasonably possible without cutting it too close.</li>
 * </ul>
 * Each job recomputes its own per-player delay every time it runs — {@code window / rosterSize}, via
 * {@link PollPacing}, floored at {@link BotConfig#getRunescapePollDelaySeconds()} so a very large
 * roster is never polled faster than that; the window guarantee slips instead of hammering the
 * RuneMetrics API. Each run is a single blocking pass that takes roughly the whole window to finish
 * (one poll, then sleep the computed delay, repeat) — giving a guild its own scheduled job rather than
 * looping over every guild in one job is what lets guilds run concurrently instead of queueing behind
 * each other.
 * <p>
 * A guild that adds a clan after boot won't get its own job until the bot restarts — same tradeoff
 * {@link ClanSyncScheduler} already makes for its daily job, for the same reason: this is a
 * single-guild bot in practice, and it's not worth reacting live to guild changes a restart already
 * handles.
 */
@BService
public class RosterPollScheduler {
    private static final Logger log = LoggerFactory.getLogger(RosterPollScheduler.class);

    private static final Duration CLAN_MEMBER_WINDOW = Duration.ofHours(3);
    private static final Duration CLAN_MEMBER_INITIAL_DELAY = Duration.ofMinutes(2);
    private static final Duration NON_CLAN_MEMBER_WINDOW = Duration.ofHours(8);
    private static final LocalTime NON_CLAN_MEMBER_ANCHOR_UTC = LocalTime.of(23, 30);

    private final ClanSyncService clanSyncService;
    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final BotConfig botConfig;
    // Sized for a handful of concurrently-polling guilds plus the one non-clan-member job — each task
    // is a long-lived-but-brief-per-thread blocking loop, not permanently occupying a thread, so this
    // comfortably covers far more guilds than this bot manages today; bump it if that ever changes.
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4,
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "roster-poll-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    public RosterPollScheduler(ClanSyncService clanSyncService, PlayerLinkService linkService,
                                RuneScapeStatsService statsService, BotConfig botConfig) {
        this.clanSyncService = clanSyncService;
        this.linkService = linkService;
        this.statsService = statsService;
        this.botConfig = botConfig;
    }

    // InjectedJDAEvent fires once the JDA object exists, not once it's actually finished populating
    // its guild cache from the gateway — enumerating jda.getGuilds() immediately in onJdaReady can (and
    // did, in testing) see zero guilds. Deferring the whole discovery+registration step by this long
    // gives the cache time to settle first; ClanSyncScheduler's own daily job sidesteps the same issue
    // by re-enumerating fresh every time it fires anyway (hours later), never right at startup.
    private static final Duration GUILD_DISCOVERY_DELAY = Duration.ofSeconds(30);

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — roster-spread polling will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        executor.schedule(() -> setUpJobs(jda), GUILD_DISCOVERY_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    private void setUpJobs(JDA jda) {
        int guildJobs = 0;
        for (Guild guild : jda.getGuilds()) {
            long guildId = guild.getIdLong();
            if (clanSyncService.getClanName(guildId) == null) continue; // nothing configured to poll

            executor.scheduleAtFixedRate(() -> pollClanRoster(guildId),
                    CLAN_MEMBER_INITIAL_DELAY.toSeconds(), CLAN_MEMBER_WINDOW.toSeconds(), TimeUnit.SECONDS);
            guildJobs++;
        }

        Duration nonClanInitialDelay = durationUntilNextUtc(NON_CLAN_MEMBER_ANCHOR_UTC);
        executor.scheduleAtFixedRate(this::pollNonClanMembers,
                nonClanInitialDelay.toSeconds(), NON_CLAN_MEMBER_WINDOW.toSeconds(), TimeUnit.SECONDS);

        log.info("Roster poll scheduler starting: {} guild(s) spreading their clan roster across every {}; " +
                        "non-clan-member linked accounts spreading across every {}, first cycle ending near {} UTC.",
                guildJobs, CLAN_MEMBER_WINDOW, NON_CLAN_MEMBER_WINDOW, NON_CLAN_MEMBER_ANCHOR_UTC);
    }

    private void pollClanRoster(long guildId) {
        try {
            var result = clanSyncService.pollActiveRosterOnly(guildId, CLAN_MEMBER_WINDOW);
            log.info("Roster-spread poll for guild {}: {}/{} clan member(s) updated successfully.",
                    guildId, result.polled(), result.polled() + result.pollFailed());
        } catch (Exception e) {
            log.error("Roster-spread poll failed for guild {}", guildId, e);
        }
    }

    /** Every linked account whose RSN is *not* currently in its own guild's active clan roster. */
    private void pollNonClanMembers() {
        List<PlayerLink> links = linkService.getAllLinksAcrossGuilds();
        if (links.isEmpty()) return;

        Map<Long, Set<String>> rosterByGuild = new HashMap<>();
        List<PlayerLink> group = links.stream().filter(link -> !isClanMember(link, rosterByGuild)).toList();
        if (group.isEmpty()) return;

        long delayMs = PollPacing.evenSpreadDelayMs(group.size(), NON_CLAN_MEMBER_WINDOW, botConfig.getRunescapePollDelaySeconds() * 1000L);
        log.info("Roster-spread poll starting for {} non-clan-member linked account(s), spaced {}ms apart.", group.size(), delayMs);
        int succeeded = 0;

        for (PlayerLink link : group) {
            try {
                boolean ok = statsService.pollAndSnapshot(link.guildId(), link.rsn()).isPresent();
                if (ok) succeeded++;
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Failed to poll stats for '{}'", link.rsn(), e);
            }
        }

        log.info("Roster-spread poll finished for non-clan-member(s): {}/{} succeeded.", succeeded, group.size());
    }

    private boolean isClanMember(PlayerLink link, Map<Long, Set<String>> rosterByGuild) {
        Set<String> roster = rosterByGuild.computeIfAbsent(link.guildId(), guildId ->
                clanSyncService.getRoster(guildId, true).stream()
                        .map(member -> member.rsn().toLowerCase())
                        .collect(Collectors.toSet()));
        return roster.contains(link.rsn().toLowerCase());
    }

    private static Duration durationUntilNextUtc(LocalTime target) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.toLocalDate().atTime(target).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Duration.between(now, candidate);
    }
}
