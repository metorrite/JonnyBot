package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.runescape.polling.PollCoordinator;
import com.younglings.bot.runescape.polling.PollJob;
import com.younglings.bot.runescape.polling.PollPriority;
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

/**
 * Keeps every tracked player's data (and the tracking feed it drives) fresh by registering the recurring poll jobs
 * with the {@link PollCoordinator}; see {@link ClanSyncScheduler} for the separate, once-a-day concern of keeping each
 * clan's roster itself (who's a member at all) up to date.
 * <p>
 * A player belongs to JonnyBot, not to a server, so there is one polling list for all of them and each name is
 * polled once per cycle however many servers it is in. The list has two tiers, each a job with its own window:
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
 * Each run works out its list afresh, so a server that registers a clan, or a player who links, joins the next run
 * without a restart. Each job spreads its players evenly across its window; the coordinator then paces them against
 * everything else the bot is polling, and skips anyone who was polled by something else within the last half window.
 * This class no longer sleeps between polls or owns a thread: it only says who to poll, how often and how urgently.
 */
@BService
public class RosterPollScheduler {
    private static final Logger log = LoggerFactory.getLogger(RosterPollScheduler.class);

    private static final Duration CLAN_INITIAL_DELAY = Duration.ofMinutes(2);
    private static final LocalTime LINKED_ANCHOR_UTC = LocalTime.of(23, 30);

    private final ClanMemberRepository clanMemberRepository;
    private final ClanSyncService clanSyncService;
    private final PlayerLinkService linkService;
    private final PollCoordinator pollCoordinator;
    private final BotConfig botConfig;

    public RosterPollScheduler(ClanMemberRepository clanMemberRepository, ClanSyncService clanSyncService, PlayerLinkService linkService,
                                PollCoordinator pollCoordinator, BotConfig botConfig) {
        this.clanMemberRepository = clanMemberRepository;
        this.clanSyncService = clanSyncService;
        this.linkService = linkService;
        this.pollCoordinator = pollCoordinator;
        this.botConfig = botConfig;
    }

    // InjectedJDAEvent fires once the JDA object exists, not once it's actually finished populating
    // its guild cache from the gateway — enumerating jda.getGuilds() immediately in onJdaReady can (and
    // did, in testing) see zero guilds. Deferring the first run by this long gives the cache time to
    // settle first; every run after that finds its servers fresh anyway.
    private static final Duration GUILD_DISCOVERY_DELAY = Duration.ofSeconds(30);

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!botConfig.getRunescapeAutoPollEnabled()) {
            log.info("RuneScape auto-poll is disabled (RUNESCAPE_AUTO_POLL_ENABLED=false) — recurring polling will not run automatically.");
            return;
        }

        JDA jda = event.getJda();
        Duration clanWindow = Duration.ofMinutes(botConfig.getRunescapeClanPollWindowMinutes());
        Duration linkedWindow = Duration.ofMinutes(botConfig.getRunescapeLinkedPollWindowMinutes());

        pollCoordinator.schedule(new PollJob("clan players", PollPriority.CLAN, clanWindow,
                CLAN_INITIAL_DELAY.compareTo(GUILD_DISCOVERY_DELAY) > 0 ? CLAN_INITIAL_DELAY : GUILD_DISCOVERY_DELAY,
                clanWindow, clanWindow.dividedBy(2), () -> clanPlayers(jda)));
        pollCoordinator.schedule(new PollJob("other linked players", PollPriority.LINKED, linkedWindow,
                durationUntilNextUtc(LINKED_ANCHOR_UTC), linkedWindow, linkedWindow.dividedBy(2),
                () -> otherLinkedPlayers(linkService.getAllAccountRsns(), clanPlayers(jda))));

        log.info("Roster poll jobs registered: players in a registered clan are spread across every {}; " +
                        "other linked players across every {}, first cycle ending near {} UTC.",
                clanWindow, linkedWindow, LINKED_ANCHOR_UTC);
    }

    /** The servers the bot is in that have a clan set up. Their rosters are the first tier. */
    private List<Long> clanGuildIds(JDA jda) {
        List<Long> ids = new ArrayList<>();
        for (Guild guild : jda.getGuilds()) {
            if (clanSyncService.getClanName(guild.getIdLong()) != null) ids.add(guild.getIdLong());
        }
        return ids;
    }

    private List<String> clanPlayers(JDA jda) {
        return clanMemberRepository.activeRsnsInGuilds(clanGuildIds(jda));
    }

    /** The second tier: registered accounts that aren't already in the first (names compared ignoring case, as RuneScape treats them). */
    static List<String> otherLinkedPlayers(List<String> accounts, List<String> clanPlayers) {
        Set<String> inClans = new HashSet<>();
        for (String rsn : clanPlayers) inClans.add(rsn.toLowerCase(Locale.ROOT));
        return accounts.stream().filter(rsn -> !inClans.contains(rsn.toLowerCase(Locale.ROOT))).toList();
    }

    private static Duration durationUntilNextUtc(LocalTime target) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime candidate = now.toLocalDate().atTime(target).atOffset(ZoneOffset.UTC);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Duration.between(now, candidate);
    }
}
