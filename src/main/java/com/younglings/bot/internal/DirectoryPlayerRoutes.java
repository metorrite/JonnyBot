package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.internal.SiteStatsRepository.MemberRow;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.ClanPointsRepository.RankConfigRow;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.younglings.bot.runescape.SkillValue;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A player's clans and stats for their public profile on runeclans.com: {@code GET players/{rsn}} answers
 * {@code {rsn, clans: [{slug, name, rank, rankOrder, maxRankOrder, firstSeen, joined, joinedExact, lastPolled}], stats: {snapshotAt, totalLevel,
 * totalXp, combatLevel, questsComplete, skills: [{id, name, level, xp, rank}], gains: {day, week, month}} | null}}.
 * <p>
 * Only clans the public directory lists, where the player's member page is public ({@link DirectoryPlayerLogic}); 404 when there is none, so a
 * hidden member looks the same as a name nobody knows; 400 for a name that isn't a RuneScape name. Everything here is already on those clans'
 * public pages, and no Discord identity is ever read. Nothing waits on Discord: it is all the bot's own database. Answers are cached for a
 * minute, by name.
 */
@BService
public class DirectoryPlayerRoutes implements DirectoryRouteModule {
    private static final long TTL_MILLIS = 60_000;
    /** Names are typed by visitors, so the cache bounds itself: past this many, it starts again. */
    private static final int MAX_CACHED = 5_000;

    private final DirectoryPlayerLogic.Source source;
    private final TtlCache cache = new TtlCache();

    public DirectoryPlayerRoutes(ClanProfileRepository profiles, ClanProfileService service, SiteApi siteApi, SiteStatsRepository stats,
                                 PlayerLinkRepository links, ClanPointsRepository points) {
        this.source = new DirectoryPlayerLogic.Source() {
            @Override
            public List<Long> guildsWithActiveMember(String rsn) {
                return stats.guildsWithActiveMember(rsn);
            }

            @Override
            public Optional<ClanProfile> profile(long guildId) {
                return profiles.get(guildId);
            }

            @Override
            public String clanName(long guildId) {
                return service.clanNameOf(guildId);
            }

            @Override
            public MemberRow visibleMember(long guildId, String rsn) {
                return siteApi.visibleMember(guildId, rsn);
            }

            @Override
            public int maxRankOrder(long guildId) {
                return points.getRanksOrdered(guildId).stream().mapToInt(RankConfigRow::rankOrder).max().orElse(0);
            }

            @Override
            public List<StatsSnapshotRow> snapshots(String rsn) {
                return links.getSnapshotHistory(rsn, DirectoryPlayerLogic.SNAPSHOTS);
            }

            @Override
            public List<SkillValue> skills(long snapshotId) {
                return links.getSkillsForSnapshot(snapshotId);
            }
        };
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("players") || parts.length != 2) return false;
        if (!request.requireGet()) return true;
        String rsn = DirectoryPlayerLogic.cleanRsn(parts[1]);
        if (rsn == null) {
            request.sendError(400, "That isn't a RuneScape name.");
            return true;
        }
        if (cache.size() > MAX_CACHED) cache.clear();
        DataObject answer = cache.get(rsn.toLowerCase(Locale.ROOT), TTL_MILLIS, () -> {
            DataObject built = DirectoryPlayerLogic.answer(rsn, source, OffsetDateTime.now(ZoneOffset.UTC));
            return built == null ? null : built.put("generatedAt", Instant.now().toString());
        });
        if (answer == null) {
            request.sendError(404, "No such player");
            return true;
        }
        request.send(200, answer);
        return true;
    }
}
