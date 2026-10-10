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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Keeps every tracked player's data (and the tracking feed it drives) fresh, spread evenly across a
 * target window instead of bursting everyone at once and then going quiet until the next cycle — see
 * {@link ClanSyncScheduler} for the separate, once-a-day concern of keeping each clan's roster itself (who's a
 * member at all) up to date.
 * <p>
 * A player belongs to JonnyBot, not to a server, so there is one polling list for all of them and each name is
 * polled once per cycle however many servers it is in. The list has two tiers, each with its own window:
 * <ul>
 *   <li><b>Clan players</b> — everyone currently in the clan of a server that has registered one, linked or not
 *       (rosters are read from every such server and merged, so a clan tracked by two servers is polled once).
 *       They are the reason for the tracking feeds, so they are refreshed often: every
 *       {@link BotConfig#getRunescapeClanPollWindowMinutes()} minutes. A poll's new activity is announced in
 *       every server whose clan the player is in (see {@link RuneScapeStatsService}).</li>
 *   <li><b>Everyone else who linked</b> — registered with JonnyBot but in no registered clan. Their profile and
 *       history only need to stay roughly current, so they are spread across
 *       {@link BotConfig#getRunescapeLinkedPollWindowMinutes()} minutes, anchored so a cycle's last poll lands right
 *       before {@link #LINKED_ANCHOR_UTC} UTC, 30 minutes ahead of RuneScape's own daily reset — that keeps the day's
 *       numbers as current as reasonably possible without cutting it too close.</li>
 * </ul>
 * Players nobody has linked and no registered clan lists are not polled at all.
 * <p>
 * Each cycle works out its list afresh, so a server that registers a clan, or a player who links, joins the next
 * cycle without a restart. The per-player delay is {@code window / listSize} via {@link PollPacing}, floored at
 * {@link BotConfig#getRunescapePollDelaySeconds()} so a very large list is never polled faster than that; the window
 * guarantee slips instead of hammering the RuneMetrics API. Each cycle is a single blocking pass that takes roughly
 * the whole window (one poll, then sleep the computed delay, repeat), and the two tiers run on their own threads so
 * neither waits for the other.
 */
@BService
public class RosterPollScheduler {
    private static final Logger log = LoggerFactory.getLogger(RosterPollScheduler.class);

    private static final Duration CLAN_INITIAL_DELAY = Duration.ofMinutes(2);
    private static final LocalTime LINKED_ANCHOR_UTC = LocalTime.of(23, 30);

    private final ClanMemberRepository clanMemberRepository;
    private final ClanSyncService clanSyncService;
    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final BotConfig botConfig;
    // One thread per tier: each cycle is a long-lived blocking loop that sleeps between polls, so they must not queue behind each other.
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2,
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "roster-poll-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    public RosterPollScheduler(ClanMemberRepository clanMemberRepository, ClanSyncService clanSyncService, PlayerLinkService linkService,
                                RuneScapeStatsService statsService, BotConfig botConfig) {
        this.clanMemberRepository = clanMemberRepository;
        this.clanSyncService = clanSyncService;
        this.linkService = linkService;
        this.statsService = statsService;
        this.botConfig = botConfig;
    }

    // InjectedJDAEvent fires once the JDA object exists, not once it's actually finished populating
    // its guild cache from the gateway — enumerating jda.getGuilds() immediately in onJdaReady can (and
    // did, in testing) see zero guilds. Deferring the first cycle by this long gives the cache time to
    // settle first; every cycle after that finds its servers fresh anyway.
    private static final Duration GUILD_DISCOVERY_DELAY = Duration.ofSeconds(30);

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — roster-spread polling will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration clanWindow = clanWindow();
        Duration linkedWindow = linkedWindow();

        executor.scheduleAtFixedRate(() -> pollClanPlayers(jda),
                Math.max(CLAN_INITIAL_DELAY.toSeconds(), GUILD_DISCOVERY_DELAY.toSeconds()), clanWindow.toSeconds(), TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(() -> pollOtherLinkedPlayers(jda),
                durationUntilNextUtc(LINKED_ANCHOR_UTC).toSeconds(), linkedWindow.toSeconds(), TimeUnit.SECONDS);

        log.info("Roster poll scheduler starting: players in a registered clan are spread across every {}; " +
                        "other linked players across every {}, first cycle ending near {} UTC.",
                clanWindow, linkedWindow, LINKED_ANCHOR_UTC);
    }

    private Duration clanWindow() {
        return Duration.ofMinutes(botConfig.getRunescapeClanPollWindowMinutes());
    }

    private Duration linkedWindow() {
        return Duration.ofMinutes(botConfig.getRunescapeLinkedPollWindowMinutes());
    }

    /** The servers the bot is in that have a clan set up. Their rosters are the first tier. */
    private List<Long> clanGuildIds(JDA jda) {
        List<Long> ids = new ArrayList<>();
        for (Guild guild : jda.getGuilds()) {
            if (clanSyncService.getClanName(guild.getIdLong()) != null) ids.add(guild.getIdLong());
        }
        return ids;
    }

    private void pollClanPlayers(JDA jda) {
        try {
            List<String> rsns = clanMemberRepository.activeRsnsInGuilds(clanGuildIds(jda));
            spreadPoll("clan players", rsns, clanWindow());
        } catch (Exception e) {
            log.error("Roster-spread poll of clan players failed", e);
        }
    }

    private void pollOtherLinkedPlayers(JDA jda) {
        try {
            List<String> clanPlayers = clanMemberRepository.activeRsnsInGuilds(clanGuildIds(jda));
            spreadPoll("other linked players", otherLinkedPlayers(linkService.getAllAccountRsns(), clanPlayers), linkedWindow());
        } catch (Exception e) {
            log.error("Roster-spread poll of other linked players failed", e);
        }
    }

    /** The second tier: registered accounts that aren't already in the first (names compared ignoring case, as RuneScape treats them). */
    static List<String> otherLinkedPlayers(List<String> accounts, List<String> clanPlayers) {
        Set<String> inClans = new HashSet<>();
        for (String rsn : clanPlayers) inClans.add(rsn.toLowerCase(Locale.ROOT));
        return accounts.stream().filter(rsn -> !inClans.contains(rsn.toLowerCase(Locale.ROOT))).toList();
    }

    /** One blocking pass over {@code rsns}, spaced evenly across {@code window}. */
    private void spreadPoll(String tier, List<String> rsns, Duration window) {
        if (rsns.isEmpty()) return;

        long delayMs = PollPacing.evenSpreadDelayMs(rsns.size(), window, botConfig.getRunescapePollDelaySeconds() * 1000L);
        log.info("Roster-spread poll starting for {} {}, spaced {}ms apart.", rsns.size(), tier, delayMs);
        int succeeded = 0;

        for (String rsn : rsns) {
            try {
                if (statsService.pollAndSnapshot(rsn).isPresent()) succeeded++;
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Failed to poll stats for '{}'", rsn, e);
            }
        }

        log.info("Roster-spread poll finished for {}: {}/{} succeeded.", tier, succeeded, rsns.size());
    }

    private static Duration durationUntilNextUtc(LocalTime target) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.toLocalDate().atTime(target).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Duration.between(now, candidate);
    }
}
