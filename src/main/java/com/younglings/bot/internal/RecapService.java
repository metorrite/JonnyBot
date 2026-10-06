package com.younglings.bot.internal;

import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.internal.ActivityKinds.Kind;
import com.younglings.bot.internal.SiteStatsRepository.ActivityRow;
import com.younglings.bot.internal.SiteStatsRepository.DayXp;
import com.younglings.bot.internal.SiteStatsRepository.Gain;
import com.younglings.bot.internal.SiteStatsRepository.MemberRow;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the numbers behind the website's recaps ("year in review"): one JSON document for the clan or for a
 * single member over a {@link RecapPeriod}. The website turns it into animated slides and a shareable image.
 * <p>
 * Everything comes from what the bot already records. Members' choices are respected: someone who left the
 * rankings is left out of every clan ranking (their XP still counts in clan totals), and someone who keeps their
 * adventure log private contributes nothing to — and gets no recap of — anything drawn from it.
 */
@BService
public class RecapService {
    private final SiteStatsRepository stats;
    private final MemberProfileRepository profiles;
    private final WeeklyDigestRepository rosterEvents;
    private final GuildSettingsService settings;

    public RecapService(SiteStatsRepository stats, MemberProfileRepository profiles, WeeklyDigestRepository rosterEvents, GuildSettingsService settings) {
        this.stats = stats;
        this.profiles = profiles;
        this.rosterEvents = rosterEvents;
        this.settings = settings;
    }

    /** @return the recap, or {@code null} for a period or member that doesn't exist */
    public DataObject build(Guild guild, String scope, String rsnRaw, String periodToken) {
        long guildId = guild.getIdLong();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RecapPeriod period = RecapPeriod.parse(periodToken, now, stats.firstSnapshotAt(guildId)).orElse(null);
        if (period == null) return null;

        if ("clan".equals(scope)) return clan(guild, period);
        if (!"member".equals(scope) || rsnRaw == null || rsnRaw.isBlank()) return null;

        MemberRow member = stats.members(guildId).stream().filter(m -> m.rsn().equalsIgnoreCase(rsnRaw.trim())).findFirst().orElse(null);
        return member == null ? null : member(guild, member, period);
    }

    // ---------- shared pieces ----------

    private static DataObject periodJson(RecapPeriod p) {
        return DataObject.empty().put("token", p.token()).put("label", p.label()).put("from", p.from().toString()).put("to", p.to().toString())
                .put("toDate", p.toDate()).put("days", p.days());
    }

    private static DataArray skillsJson(List<SiteStatsRepository.SkillGain> gains, int limit) {
        DataArray array = DataArray.empty();
        gains.stream().limit(limit).forEach(g -> array.add(DataObject.empty().put("skillId", g.skillId()).put("skill", RuneScapeSkillCatalog.nameFor(g.skillId())).put("xp", g.xp())));
        return array;
    }

    private static DataObject xpJson(long total, List<DayXp> daily, RecapPeriod period) {
        DataObject best = null;
        long bestXp = 0;
        int activeDays = 0;
        TreeMap<LocalDate, Long> byDay = new TreeMap<>();
        for (DayXp d : daily) {
            byDay.put(d.date(), d.xp());
            if (d.xp() > 0) activeDays++;
            if (d.xp() > bestXp) {
                bestXp = d.xp();
                best = DataObject.empty().put("date", d.date().toString()).put("xp", d.xp());
            }
        }

        // The weekday the XP piles up on, by total.
        long[] perWeekday = new long[7];
        daily.forEach(d -> perWeekday[d.date().getDayOfWeek().getValue() - 1] += d.xp());
        int busiest = 0;
        for (int i = 1; i < 7; i++) if (perWeekday[i] > perWeekday[busiest]) busiest = i;

        DataArray series = DataArray.empty();
        // Long windows (a year, all time) are folded into weeks so the chart stays readable.
        if (period.days() > 62) {
            TreeMap<LocalDate, Long> weekly = new TreeMap<>();
            byDay.forEach((date, xp) -> weekly.merge(RecapPeriod.citadelWeekStart(date), xp, Long::sum));
            weekly.forEach((week, xp) -> series.add(DataObject.empty().put("date", week.toString()).put("xp", xp)));
        } else {
            byDay.forEach((date, xp) -> series.add(DataObject.empty().put("date", date.toString()).put("xp", xp)));
        }

        return DataObject.empty().put("total", total).put("series", series).put("bySeriesUnit", period.days() > 62 ? "week" : "day")
                .put("bestDay", best).put("activeDays", activeDays).put("perDay", total / period.days())
                .put("busiestWeekday", bestXp == 0 ? null : DayOfWeek.of(busiest + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH));
    }

    /** What the adventure-log lines in the window add up to. */
    private static final class Tally {
        long bossKills, drops, levelUps, quests, caps, xpMilestones, twoHundredM;
        final Map<String, Long> bosses = new HashMap<>();
        final Map<String, Long> killers = new HashMap<>();
        final Map<String, Long> dropItems = new HashMap<>();
        final Set<String> capWeeks = new java.util.HashSet<>();
    }

    private static Tally tally(List<ActivityRow> rows, Set<String> hidden) {
        Tally t = new Tally();
        for (ActivityRow row : rows) {
            if (hidden.contains(row.rsn().toLowerCase())) continue;
            switch (ActivityKinds.kindOf(row.text())) {
                case BOSS -> {
                    int kills = ActivityKinds.killCount(row.text());
                    t.bossKills += kills;
                    ActivityKinds.bossOf(row.text()).ifPresent(b -> t.bosses.merge(b, (long) kills, Long::sum));
                    t.killers.merge(row.rsn(), (long) kills, Long::sum);
                }
                case DROP -> {
                    t.drops++;
                    ActivityKinds.dropOf(row.text()).ifPresent(d -> t.dropItems.merge(d, 1L, Long::sum));
                }
                case LEVEL_UP -> t.levelUps++;
                case QUEST -> t.quests++;
                case XP_MILESTONE -> {
                    t.xpMilestones++;
                    if (row.text().startsWith("200000000")) t.twoHundredM++;
                }
                case CITADEL_CAP -> {
                    t.caps++;
                    t.capWeeks.add(row.rsn().toLowerCase() + "|" + RecapPeriod.citadelWeekStart(row.recordedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()));
                }
                default -> { /* visits, clues, pets and the rest aren't part of the recap */ }
            }
        }
        return t;
    }

    private static DataArray topMap(Map<String, Long> map, String nameKey, String valueKey, int limit) {
        DataArray array = DataArray.empty();
        map.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(limit)
                .forEach(e -> array.add(DataObject.empty().put(nameKey, e.getKey()).put(valueKey, e.getValue())));
        return array;
    }

    private static DataObject activityJson(Tally t, boolean hidden) {
        if (hidden) return DataObject.empty().put("hidden", true);
        return DataObject.empty().put("hidden", false)
                .put("bossKills", t.bossKills).put("topBosses", topMap(t.bosses, "boss", "kills", 5))
                .put("drops", t.drops).put("topDrops", topMap(t.dropItems, "item", "count", 5))
                .put("levelUps", t.levelUps).put("quests", t.quests).put("xpMilestones", t.xpMilestones).put("twoHundredM", t.twoHundredM)
                .put("caps", t.caps);
    }

    private static DataArray gainersJson(List<Gain> gains, int limit) {
        DataArray array = DataArray.empty();
        gains.stream().filter(g -> g.xp() > 0).sorted(Comparator.comparingLong(Gain::xp).reversed()).limit(limit)
                .forEach(g -> array.add(DataObject.empty().put("rsn", g.rsn()).put("xp", g.xp())));
        return array;
    }

    // ---------- the clan ----------

    private DataObject clan(Guild guild, RecapPeriod period) {
        long guildId = guild.getIdLong();
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        Set<String> hiddenLogs = profiles.hiddenRsns(guildId, false);

        List<Gain> allGains = stats.xpGainsBetween(guildId, period.from(), period.to());
        long totalXp = allGains.stream().mapToLong(Gain::xp).filter(x -> x > 0).sum();
        List<Gain> ranked = allGains.stream().filter(g -> !hiddenBoards.contains(g.rsn().toLowerCase())).toList();

        Tally tally = tally(stats.activitiesBetween(guildId, null, period.from(), period.to()), hiddenLogs);

        long capWeeksCount = tally.capWeeks.size();
        DataArray cappers = DataArray.empty();
        stats.cappersBetween(guildId, period.from(), period.to(), 40).stream().filter(c -> !hiddenBoards.contains(c.rsn().toLowerCase())).limit(5)
                .forEach(c -> cappers.add(DataObject.empty().put("rsn", c.rsn()).put("weeksCapped", c.weeksCapped()).put("totalCaps", c.totalCaps())));

        DataArray joined = DataArray.empty();
        int left = 0;
        int joinedCount = 0;
        for (var event : rosterEvents.getRosterEventsInWindow(guildId, period.from(), period.to())) {
            if (event.eventType().equals("JOIN")) {
                joinedCount++;
                if (joined.length() < 12) joined.add(event.rsn());
            } else {
                left++;
            }
        }

        long points = stats.pointsBetween(guildId, null, period.from(), period.to()).stream().mapToLong(SiteStatsRepository.PointsEarned::points).sum();

        DataArray killers = topMap(tally.killers.entrySet().stream().filter(e -> !hiddenBoards.contains(e.getKey().toLowerCase()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)), "rsn", "kills", 5);

        return DataObject.empty()
                .put("scope", "clan").put("period", periodJson(period))
                .put("subject", DataObject.empty().put("name", settings.getEffective(guildId).clanName() == null ? "The clan" : settings.getEffective(guildId).clanName())
                        .put("memberCount", stats.members(guildId).size()))
                .put("xp", xpJson(totalXp, stats.dailyXpBetween(guildId, null, period.from(), period.to()), period))
                .put("skills", skillsJson(stats.skillGainsBetween(guildId, null, period.from(), period.to()), 8))
                .put("topGainers", gainersJson(ranked, 5))
                .put("activeMembers", allGains.stream().filter(g -> g.xp() > 0).count())
                .put("activity", activityJson(tally, false))
                .put("citadel", DataObject.empty().put("capsRecorded", tally.caps).put("capWeeks", capWeeksCount).put("topCappers", cappers))
                .put("topKillers", killers)
                .put("roster", DataObject.empty().put("joined", joinedCount).put("left", left).put("newMembers", joined))
                .put("points", points);
    }

    // ---------- one member ----------

    private DataObject member(Guild guild, MemberRow member, RecapPeriod period) {
        long guildId = guild.getIdLong();
        Set<String> hiddenBoards = profiles.hiddenRsns(guildId, true);
        boolean logHidden = profiles.hiddenRsns(guildId, false).contains(member.rsn().toLowerCase());

        List<Gain> allGains = stats.xpGainsBetween(guildId, period.from(), period.to());
        long mine = allGains.stream().filter(g -> g.rsn().equalsIgnoreCase(member.rsn())).mapToLong(Gain::xp).findFirst().orElse(0);

        List<Gain> ranked = allGains.stream().filter(g -> g.xp() > 0 && !hiddenBoards.contains(g.rsn().toLowerCase())).sorted(Comparator.comparingLong(Gain::xp).reversed()).toList();
        int rank = 0;
        for (int i = 0; i < ranked.size(); i++) if (ranked.get(i).rsn().equalsIgnoreCase(member.rsn())) rank = i + 1;
        boolean inRankings = rank > 0 && !hiddenBoards.contains(member.rsn().toLowerCase());
        long clanTotal = allGains.stream().mapToLong(Gain::xp).filter(x -> x > 0).sum();
        long gainers = allGains.stream().filter(g -> g.xp() > 0).count();

        Tally tally = logHidden ? new Tally() : tally(stats.activitiesBetween(guildId, member.rsn(), period.from(), period.to()), Set.of());

        Integer[] levels = stats.totalLevelsBetween(guildId, member.rsn(), period.from(), period.to());
        long points = stats.pointsBetween(guildId, member.rsn(), period.from(), period.to()).stream().mapToLong(SiteStatsRepository.PointsEarned::points).sum();

        // Longest run of consecutive Citadel weeks capped inside the window.
        List<SiteStatsRepository.CapWeek> capWeeks = stats.allCapWeeks(guildId).stream()
                .filter(c -> c.rsn().equalsIgnoreCase(member.rsn()) && !c.weekStart().atStartOfDay().atOffset(ZoneOffset.UTC).isBefore(RecapPeriod.citadelWeekStart(period.from().toLocalDate()).atStartOfDay().atOffset(ZoneOffset.UTC))
                        && c.weekStart().atStartOfDay().atOffset(ZoneOffset.UTC).isBefore(period.to()))
                .toList();
        int streak = capWeeks.isEmpty() ? 0 : SiteApi.capStreaks(capWeeks, RecapPeriod.citadelWeekStart(period.to().toLocalDate())).getFirst().longest();

        DataObject ranking = DataObject.empty().put("inRankings", inRankings).put("rank", inRankings ? rank : null).put("outOf", ranked.size())
                .put("clanAveragePerMember", gainers == 0 ? 0 : clanTotal / gainers)
                .put("shareOfClan", clanTotal == 0 ? 0.0 : Math.round(mine * 1000.0 / clanTotal) / 10.0)
                .put("topPercent", inRankings && !ranked.isEmpty() ? Math.max(1, (int) Math.round(rank * 100.0 / ranked.size())) : null);

        return DataObject.empty()
                .put("scope", "member").put("period", periodJson(period))
                .put("subject", DataObject.empty().put("name", member.rsn()).put("rank", member.clanRank()).put("joined", (member.clanJoinedAt() != null ? member.clanJoinedAt() : member.firstSeen().toLocalDate()).toString())
                        .put("totalLevel", member.totalLevel()).put("totalXp", member.totalXp()))
                .put("xp", xpJson(mine, stats.dailyXpBetween(guildId, member.rsn(), period.from(), period.to()), period))
                .put("skills", skillsJson(stats.skillGainsBetween(guildId, member.rsn(), period.from(), period.to()), 8))
                .put("levels", DataObject.empty().put("start", levels[0]).put("end", levels[1]).put("gained", levels[0] == null || levels[1] == null ? null : Math.max(0, levels[1] - levels[0])))
                .put("ranking", ranking)
                .put("activity", activityJson(tally, logHidden))
                .put("citadel", DataObject.empty().put("capsRecorded", tally.caps).put("weeksCapped", capWeeks.size()).put("longestStreak", streak))
                .put("points", points);
    }
}
