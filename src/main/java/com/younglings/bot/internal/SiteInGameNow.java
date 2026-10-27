package com.younglings.bot.internal;

import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.plugin.RuneSyncRepository;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "In game now" on a clan site (T-586): who in the clan is logged in to RuneScape with RuneSync right now, from the check-ins RuneSync already
 * makes ({@code plugin_client_status}), for members who switched on the Presence category. The rules are {@link InGameNowRules}. Works for a
 * site without the bot too, by matching RuneLink names against the clan's roster.
 * <p>
 * Served at {@code GET sites/{slug}/data/plugin/in-game} ({@link DirectoryClanSiteRoutes}). The website shows the list only to the viewers the
 * site allows (signed-in clan members by default). Answers are cached {@link #CACHE} per server.
 */
@BService
public class SiteInGameNow {
    private static final Logger log = LoggerFactory.getLogger(SiteInGameNow.class);
    static final Duration CACHE = Duration.ofSeconds(30);

    private record Cached(Instant at, DataObject answer) {}

    private final RuneSyncRepository runeSync;
    private final PlayerLinkRepository links;
    private final SiteStatsRepository stats;
    private final ClanSiteLayoutRepository layouts;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public SiteInGameNow(RuneSyncRepository runeSync, PlayerLinkRepository links, SiteStatsRepository stats, ClanSiteLayoutRepository layouts) {
        this.runeSync = runeSync;
        this.links = links;
        this.stats = stats;
        this.layouts = layouts;
    }

    /** Tests: a fixed clock. */
    SiteInGameNow withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * {@code {available, placed, visibility, windowMinutes, count, members:[{rsn}]}} for the site of {@code guildId}; {@code guild} is null for a
     * site whose server doesn't have the bot. {@code available} false: the bot couldn't read it just now.
     */
    public DataObject answer(long guildId, Guild guild) {
        Instant now = clock.instant();
        Cached c = cache.get(guildId);
        if (c != null && c.at().plus(CACHE).isAfter(now)) return c.answer();
        DataObject answer;
        try {
            answer = compute(guildId, guild, now);
        } catch (RuntimeException e) {
            log.warn("In game now for server {} failed: {}", guildId, e.getMessage());
            return DataObject.empty().put("available", false).put("placed", true).put("visibility", "members")
                    .put("windowMinutes", InGameNowRules.WINDOW.toMinutes()).put("count", 0).put("unnamed", 0).put("members", DataArray.empty());
        }
        if (cache.size() > 5_000) cache.clear();
        cache.put(guildId, new Cached(now, answer));
        return answer;
    }

    DataObject compute(long guildId, Guild guild, Instant now) {
        String visibility = layouts.byGuild(guildId).map(ClanSiteLayoutRepository.Layouts::published).filter(raw -> raw != null && !raw.isBlank())
                .map(SiteInGameNow::parse).map(InGameNowRules::visibility).orElse(null);
        if (visibility == null) return InGameNowRules.notPlaced();
        List<RuneSyncRepository.Present> present = runeSync.presentSince(now.minus(InGameNowRules.WINDOW));
        if (present.isEmpty()) return InGameNowRules.answer(visibility, List.of(), 0);
        List<String> roster = stats.members(guildId).stream().map(SiteStatsRepository.MemberRow::rsn).toList();
        Set<String> rosterKeys = InGameNowRules.lower(roster);
        List<String> names = new ArrayList<>();
        int unnamed = 0;
        for (RuneSyncRepository.Present p : present) {
            Member member = guild == null ? null : guild.getMemberById(p.discordUserId());
            // the character playing first (its RuneLink name), then the names linked in this server, then their other linked names
            List<String> all = new ArrayList<>();
            if (p.rsn() != null) all.add(p.rsn());
            if (guild != null) links.getLinksForUser(guildId, p.discordUserId()).stream().map(PlayerLink::rsn).forEach(all::add);
            links.getAccountsForUser(p.discordUserId()).stream().map(PlayerLinkRepository.PlayerAccount::rsn).forEach(all::add);
            if (!InGameNowRules.inClan(member != null, all, rosterKeys)) continue;
            String name = InGameNowRules.displayName(all, roster);
            if (name != null) names.add(name);
            else unnamed++;
        }
        return InGameNowRules.answer(visibility, InGameNowRules.sorted(names), unnamed);
    }

    private static DataObject parse(String raw) {
        try {
            return DataObject.fromJson(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
