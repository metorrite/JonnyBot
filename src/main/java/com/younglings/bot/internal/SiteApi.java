package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.internal.SiteStatsRepository.Gain;
import com.younglings.bot.internal.SiteStatsRepository.MemberRow;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.ClanPointsRepository.RankConfigRow;
import com.younglings.bot.runescape.PlayerActivity;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkRepository.SkillHistoryPoint;
import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.SkillValue;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

/**
 * The public-facing half of the internal API, under {@code /internal/site/}: clan roster, leaderboards,
 * charts, member profiles, the live "who's online" list and Discord events — everything the website shows to
 * anyone, logged in or not. {@link InternalApiServer} has already checked the shared secret and found the
 * guild; these routes are GET-only and need no acting user, because none of this is private.
 * <p>
 * What it deliberately leaves out: Discord identities. A member's RuneScape name, rank, stats and adventure
 * log are what the clan page shows; whether that name is linked to a Discord account is reduced to a
 * "verified" flag, and the link itself (who it is, their Discord id) never leaves the bot.
 */
@BService
public class SiteApi {
    private static final Logger log = LoggerFactory.getLogger(SiteApi.class);
    private static final String PREFIX = "/internal/site/";
    private static final int HISTORY_DAYS = 90;
    private static final int CHART_WEEKS = 12;

    private final SiteStatsRepository stats;
    private final PlayerLinkRepository links;
    private final ClanPointsRepository points;
    private final GuildSettingsService settings;

    public SiteApi(SiteStatsRepository stats, PlayerLinkRepository links, ClanPointsRepository points, GuildSettingsService settings) {
        this.stats = stats;
        this.links = links;
        this.points = points;
        this.settings = settings;
    }

    public void handle(HttpExchange exchange, Guild guild) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);

            DataObject result = switch (route) {
                case "online" -> online(guild);
                case "events" -> events(guild);
                case "members" -> members(guild);
                case "overview" -> overview(guild);
                case "member" -> member(guild, query(exchange, "rsn"));
                default -> null;
            };

            if (result == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
            } else {
                InternalApiServer.sendJson(exchange, 200, result);
            }
        } catch (Exception e) {
            log.error("Site API request {} failed", exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // ---------- who's online, in the order Discord's own member list uses ----------

    /**
     * Online members grouped the way Discord's member list groups them: under their highest <em>hoisted</em>
     * role (the roles set to "display separately"), groups ordered by that role's position, everyone else
     * under a trailing "Online" group, and alphabetical (ignoring case) inside each group.
     */
    DataObject online(Guild guild) {
        record Group(long id, String name, int colorRaw, int position) {}
        Map<Long, List<Member>> byGroup = new HashMap<>();
        Map<Long, Group> groups = new HashMap<>();
        int total = 0;

        for (Member member : guild.getMembers()) {
            if (member.getUser().isBot()) continue;
            OnlineStatus status = member.getOnlineStatus();
            if (status == OnlineStatus.OFFLINE || status == OnlineStatus.UNKNOWN) continue;

            Role hoisted = member.getRoles().stream().filter(Role::isHoisted).findFirst().orElse(null);
            long key = hoisted == null ? 0 : hoisted.getIdLong();
            groups.computeIfAbsent(key, k -> hoisted == null
                    ? new Group(0, "Online", -1, Integer.MIN_VALUE)
                    : new Group(hoisted.getIdLong(), hoisted.getName(), hoisted.getColors().getPrimaryRaw(), hoisted.getPosition()));
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(member);
            total++;
        }

        DataArray groupArray = DataArray.empty();
        groups.values().stream().sorted(Comparator.comparingInt(Group::position).reversed()).forEach(group -> {
            List<Member> members = byGroup.get(group.id());
            members.sort(Comparator.comparing(m -> m.getEffectiveName().toLowerCase()));

            DataArray memberArray = DataArray.empty();
            for (Member m : members) {
                List<Role> roles = m.getRoles();
                Role top = roles.isEmpty() ? null : roles.getFirst();
                memberArray.add(DataObject.empty()
                        .put("id", m.getId())
                        .put("displayName", m.getEffectiveName())
                        .put("avatarUrl", m.getEffectiveAvatarUrl())
                        .put("status", m.getOnlineStatus().getKey())
                        .put("colorRaw", m.getColors().getPrimaryRaw())
                        .put("topRole", top == null ? null : top.getName()));
            }
            groupArray.add(DataObject.empty()
                    .put("name", group.name())
                    .put("colorRaw", group.colorRaw())
                    .put("count", members.size())
                    .put("members", memberArray));
        });

        return DataObject.empty().put("total", total).put("groups", groupArray);
    }

    // ---------- events ----------

    DataObject events(Guild guild) {
        DataArray events = DataArray.empty();
        guild.getScheduledEvents().stream()
                .sorted(Comparator.comparing(ScheduledEvent::getStartTime))
                .forEach(event -> events.add(DataObject.empty()
                        .put("id", event.getId())
                        .put("name", event.getName())
                        .put("description", event.getDescription())
                        .put("imageUrl", event.getImageUrl())
                        .put("location", event.getLocation())
                        .put("status", event.getStatus().name())
                        .put("startTime", event.getStartTime().toString())
                        .put("endTime", event.getEndTime() == null ? null : event.getEndTime().toString())
                        .put("interestedCount", event.getInterestedUserCount())
                        .put("url", "https://discord.com/events/" + guild.getId() + "/" + event.getId())));
        return DataObject.empty().put("events", events);
    }

    // ---------- the roster ----------

    private static DataObject memberJson(MemberRow m) {
        return DataObject.empty()
                .put("rsn", m.rsn())
                .put("rank", m.clanRank())
                .put("rankOrder", m.rankOrder())
                .put("totalXp", m.totalXp())
                .put("kills", m.kills())
                .put("joined", (m.clanJoinedAt() != null ? m.clanJoinedAt() : m.firstSeen().toLocalDate()).toString())
                .put("joinedExact", m.clanJoinedAt() != null)
                .put("points", m.points())
                .put("promotionNeeded", m.promotionNeeded())
                .put("verified", m.verified())
                .put("totalLevel", m.totalLevel())
                .put("combatLevel", m.combatLevel());
    }

    private DataArray ranksJson(long guildId, List<MemberRow> members) {
        Map<Integer, Integer> counts = new HashMap<>();
        members.forEach(m -> counts.merge(m.rankOrder(), 1, Integer::sum));

        DataArray ranks = DataArray.empty();
        for (RankConfigRow rank : points.getRanksOrdered(guildId)) {
            ranks.add(DataObject.empty()
                    .put("name", rank.rankName())
                    .put("order", rank.rankOrder())
                    .put("threshold", rank.pointThreshold())
                    .put("count", counts.getOrDefault(rank.rankOrder(), 0)));
        }
        return ranks;
    }

    DataObject members(Guild guild) {
        List<MemberRow> members = stats.members(guild.getIdLong());
        DataArray array = DataArray.empty();
        members.stream()
                .sorted(Comparator.comparingInt(MemberRow::rankOrder).reversed().thenComparing(m -> m.rsn().toLowerCase()))
                .forEach(m -> array.add(memberJson(m)));
        return DataObject.empty().put("members", array).put("ranks", ranksJson(guild.getIdLong(), members));
    }

    // ---------- clan overview: leaderboards and charts ----------

    private static DataArray gainsJson(List<Gain> gains, int limit) {
        DataArray array = DataArray.empty();
        gains.stream().filter(g -> g.xp() > 0).sorted(Comparator.comparingLong(Gain::xp).reversed()).limit(limit)
                .forEach(g -> array.add(DataObject.empty().put("rsn", g.rsn()).put("xp", g.xp())));
        return array;
    }

    private static DataArray topBy(List<MemberRow> members, ToLongFunction<MemberRow> metric, int limit) {
        DataArray array = DataArray.empty();
        members.stream().filter(m -> metric.applyAsLong(m) > 0)
                .sorted(Comparator.comparingLong(metric).reversed()).limit(limit)
                .forEach(m -> array.add(DataObject.empty().put("rsn", m.rsn()).put("value", metric.applyAsLong(m))));
        return array;
    }

    DataObject overview(Guild guild) {
        long guildId = guild.getIdLong();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<MemberRow> members = stats.members(guildId);

        List<Gain> day = stats.xpGains(guildId, now.minusDays(1));
        List<Gain> week = stats.xpGains(guildId, now.minusDays(7));
        List<Gain> month = stats.xpGains(guildId, now.minusDays(30));

        DataObject clan = DataObject.empty()
                .put("name", settings.getEffective(guildId).clanName())
                .put("memberCount", members.size())
                .put("verifiedCount", members.stream().filter(MemberRow::verified).count())
                .put("totalXp", members.stream().mapToLong(MemberRow::totalXp).sum())
                .put("totalKills", members.stream().mapToLong(MemberRow::kills).sum())
                .put("averageTotalLevel", Math.round(members.stream().filter(m -> m.totalLevel() != null).mapToInt(MemberRow::totalLevel).average().orElse(0)))
                .put("xpToday", day.stream().mapToLong(Gain::xp).sum())
                .put("xpWeek", week.stream().mapToLong(Gain::xp).sum())
                .put("xpMonth", month.stream().mapToLong(Gain::xp).sum());

        DataObject gains = DataObject.empty()
                .put("day", gainsJson(day, 10)).put("week", gainsJson(week, 10)).put("month", gainsJson(month, 10));

        DataArray citadelWeeks = DataArray.empty();
        for (var w : stats.citadelByWeek(guildId, CHART_WEEKS)) {
            citadelWeeks.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("capped", w.capped()).put("visited", w.visited()));
        }
        DataArray cappers = DataArray.empty();
        for (var c : stats.topCappers(guildId, 10)) {
            cappers.add(DataObject.empty().put("rsn", c.rsn()).put("weeksCapped", c.weeksCapped()).put("totalCaps", c.totalCaps()));
        }

        DataArray roster = DataArray.empty();
        for (var w : stats.rosterByWeek(guildId, CHART_WEEKS)) {
            roster.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("joins", w.joins()).put("leaves", w.leaves()));
        }

        Map<Integer, List<SiteStatsRepository.SkillLeader>> bySkill = new TreeMap<>();
        stats.skillLeaders(guildId, 3).forEach(l -> bySkill.computeIfAbsent(l.skillId(), k -> new ArrayList<>()).add(l));
        DataArray skills = DataArray.empty();
        bySkill.forEach((skillId, leaders) -> {
            DataArray array = DataArray.empty();
            leaders.forEach(l -> array.add(DataObject.empty().put("rsn", l.rsn()).put("level", l.level()).put("xp", l.xp())));
            skills.add(DataObject.empty().put("skillId", skillId).put("skill", RuneScapeSkillCatalog.nameFor(skillId)).put("leaders", array));
        });

        return DataObject.empty()
                .put("clan", clan)
                .put("ranks", ranksJson(guildId, members))
                .put("gains", gains)
                .put("citadel", DataObject.empty().put("weeks", citadelWeeks).put("topCappers", cappers))
                .put("roster", roster)
                .put("skillLeaders", skills)
                .put("topXp", topBy(members, MemberRow::totalXp, 10))
                .put("topKills", topBy(members, MemberRow::kills, 10))
                .put("topLevel", topBy(members, m -> m.totalLevel() == null ? 0 : m.totalLevel(), 10))
                .put("topPoints", topBy(members, MemberRow::points, 10));
    }

    // ---------- one member's profile ----------

    /** XP gained from {@code since} to the newest snapshot; snapshots are newest first. */
    static long gainSince(List<StatsSnapshotRow> newestFirst, OffsetDateTime since) {
        if (newestFirst.size() < 2) return 0;
        long latest = newestFirst.getFirst().totalXp();
        StatsSnapshotRow baseline = null;
        for (StatsSnapshotRow row : newestFirst) {
            if (!row.snapshotAt().isAfter(since)) {
                baseline = row;
                break;
            }
        }
        if (baseline == null) baseline = newestFirst.getLast(); // history doesn't reach back that far — use what there is
        return Math.max(0, latest - baseline.totalXp());
    }

    /** Per-skill XP gained since {@code since}, from a baseline at or before it where one exists. */
    static Map<Integer, Long> skillGainsSince(List<SkillHistoryPoint> points, OffsetDateTime since) {
        Map<Integer, List<SkillHistoryPoint>> bySkill = new HashMap<>();
        points.forEach(p -> bySkill.computeIfAbsent(p.skillId(), k -> new ArrayList<>()).add(p));

        Map<Integer, Long> gains = new LinkedHashMap<>();
        bySkill.forEach((skillId, series) -> {
            series.sort(Comparator.comparing(SkillHistoryPoint::timestamp));
            long latest = series.getLast().xp();
            SkillHistoryPoint baseline = series.getFirst();
            for (SkillHistoryPoint p : series) {
                if (p.timestamp().isAfter(since)) break;
                baseline = p;
            }
            long gain = latest - baseline.xp();
            if (gain > 0) gains.put(skillId, gain);
        });
        return gains;
    }

    DataObject member(Guild guild, String rsn) {
        if (rsn == null || rsn.isBlank()) return null;
        long guildId = guild.getIdLong();

        MemberRow row = stats.members(guildId).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn.trim())).findFirst().orElse(null);
        if (row == null) return null;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<StatsSnapshotRow> snapshots = links.getSnapshotHistory(guildId, row.rsn(), 2000);
        StatsSnapshotRow latest = snapshots.isEmpty() ? null : snapshots.getFirst();

        DataObject json = memberJson(row)
                .put("firstSeen", row.firstSeen().toString())
                .put("lastPolled", row.lastPolled() == null ? null : row.lastPolled().toString())
                .put("questsComplete", latest == null ? null : latest.questsComplete());

        // Progress toward the next rank, by clan points.
        List<RankConfigRow> ranks = points.getRanksOrdered(guildId);
        RankConfigRow next = ranks.stream().filter(r -> r.rankOrder() > row.rankOrder()).min(Comparator.comparingInt(RankConfigRow::rankOrder)).orElse(null);
        json.put("nextRank", next == null ? null : DataObject.empty()
                .put("name", next.rankName())
                .put("threshold", next.pointThreshold())
                .put("pointsNeeded", Math.max(0, next.pointThreshold() - row.points())));

        // Skills now.
        DataArray skills = DataArray.empty();
        if (latest != null) {
            for (SkillValue skill : links.getSkillsForSnapshot(latest.snapshotId())) {
                skills.add(DataObject.empty().put("id", skill.skillId()).put("name", RuneScapeSkillCatalog.nameFor(skill.skillId()))
                        .put("level", skill.level()).put("xp", skill.xp()).put("rank", skill.rank()));
            }
        }
        json.put("skills", skills);

        // Gains: totals and per skill.
        json.put("gains", DataObject.empty()
                .put("day", gainSince(snapshots, now.minus(Duration.ofDays(1))))
                .put("week", gainSince(snapshots, now.minus(Duration.ofDays(7))))
                .put("month", gainSince(snapshots, now.minus(Duration.ofDays(30)))));

        List<SkillHistoryPoint> skillHistory = links.getAllSkillsXpHistorySince(guildId, row.rsn(), now.minusDays(32));
        json.put("skillGains", DataObject.empty()
                .put("day", skillGainsJson(skillGainsSince(skillHistory, now.minusDays(1))))
                .put("week", skillGainsJson(skillGainsSince(skillHistory, now.minusDays(7))))
                .put("month", skillGainsJson(skillGainsSince(skillHistory, now.minusDays(30)))));

        // Daily history for the charts.
        DataArray history = DataArray.empty();
        for (var point : stats.dailyHistory(guildId, row.rsn(), HISTORY_DAYS)) {
            history.add(DataObject.empty().put("date", point.date().toString()).put("totalXp", point.totalXp()).put("totalLevel", point.totalLevel()));
        }
        json.put("history", history);

        // Citadel record.
        var citadel = stats.citadelFor(guildId, row.rsn());
        DataArray weeks = DataArray.empty();
        for (LocalDate week : citadel.cappedWeeks()) weeks.add(week.toString());
        json.put("citadel", DataObject.empty().put("caps", citadel.caps()).put("visits", citadel.visits()).put("cappedWeeks", weeks));

        // Adventure log.
        DataArray activities = DataArray.empty();
        for (PlayerActivity activity : links.getRecentActivities(guildId, row.rsn(), 30)) {
            activities.add(DataObject.empty().put("date", activity.date()).put("text", activity.text()).put("details", activity.details()));
        }
        json.put("activities", activities);

        return json;
    }

    private static DataArray skillGainsJson(Map<Integer, Long> gains) {
        DataArray array = DataArray.empty();
        gains.entrySet().stream().sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).forEach(e -> array.add(
                DataObject.empty().put("skillId", e.getKey()).put("skill", RuneScapeSkillCatalog.nameFor(e.getKey())).put("xp", e.getValue())));
        return array;
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
