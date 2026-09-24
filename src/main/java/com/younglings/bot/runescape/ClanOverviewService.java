package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregates every active clan member's stats into one clan-wide picture, the same way
 * {@link MonthlyRecapService} does for a single player — same two heuristics apply here for the
 * same reason (see that class's doc): "capped" is a plain substring match, and "most challenged"
 * only recognizes the "Won a challenge against the X Champion" phrasing actually observed live.
 */
@BService
public class ClanOverviewService {
    private static final Pattern CHAMPION_WIN = Pattern.compile(
            "Won a challenge against the (.+?) Champion", Pattern.CASE_INSENSITIVE);
    private static final long MAX_SKILL_XP = 200_000_000L;
    private static final int TOP_LEADERBOARD_SIZE = 15;

    private final ClanSyncService clanSyncService;
    private final RuneScapeStatsService statsService;
    private final PlayerLinkService linkService;

    public ClanOverviewService(ClanSyncService clanSyncService, RuneScapeStatsService statsService, PlayerLinkService linkService) {
        this.clanSyncService = clanSyncService;
        this.statsService = statsService;
        this.linkService = linkService;
    }

    public record MemberTotal(String rsn, long value) {}

    public record ClanOverviewStats(int totalMembers, int verifiedMembers, int membersWithData,
                                     long totalCombinedXp, long totalXpGainedThisMonth, int totalQuestsComplete,
                                     int membersWithMaxedSkill, int membersWith120Skill,
                                     List<MemberTotal> topByTotalXp,
                                     String mostChallenged, int mostChallengedCount, int timesCapped) {
    }

    /** {@code null} if the roster is empty (nothing tracked yet — run Sync Clan first). */
    public ClanOverviewStats getOverview(long guildId) {
        List<ClanMemberRepository.ClanMemberRow> roster = clanSyncService.getRoster(guildId, true);
        if (roster.isEmpty()) return null;

        OffsetDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        int verifiedMembers = 0;
        int membersWithData = 0;
        long totalCombinedXp = 0;
        long totalXpGainedThisMonth = 0;
        int totalQuestsComplete = 0;
        int membersWithMaxedSkill = 0;
        int membersWith120Skill = 0;
        int timesCapped = 0;
        Map<String, Integer> championWins = new HashMap<>();
        List<MemberTotal> totals = new ArrayList<>();

        for (ClanMemberRepository.ClanMemberRow member : roster) {
            if (linkService.getLinkForRsn(guildId, member.rsn()) != null) verifiedMembers++;

            PlayerLinkRepository.StatsSnapshotRow latest = statsService.getLatestSnapshot(guildId, member.rsn());
            if (latest == null) continue;

            membersWithData++;
            totalCombinedXp += latest.totalXp();
            totalQuestsComplete += latest.questsComplete();
            totals.add(new MemberTotal(member.rsn(), latest.totalXp()));

            boolean hasMaxedSkill = false;
            boolean has120Skill = false;
            for (SkillValue skill : statsService.getSkillsForSnapshot(latest.snapshotId())) {
                if (skill.xp() >= MAX_SKILL_XP) hasMaxedSkill = true;
                if (skill.level() >= 120) has120Skill = true;
            }
            if (hasMaxedSkill) membersWithMaxedSkill++;
            if (has120Skill) membersWith120Skill++;

            List<PlayerLinkRepository.StatsSnapshotRow> monthSnapshots = statsService.getSnapshotsSince(guildId, member.rsn(), monthStart);
            if (monthSnapshots.size() >= 2) {
                totalXpGainedThisMonth += monthSnapshots.getLast().totalXp() - monthSnapshots.getFirst().totalXp();
            }

            for (PlayerActivity activity : statsService.getActivitiesSince(guildId, member.rsn(), monthStart)) {
                if (activity.text().toLowerCase(Locale.ROOT).contains("capped")) timesCapped++;

                Matcher matcher = CHAMPION_WIN.matcher(activity.text());
                if (matcher.find()) championWins.merge(matcher.group(1).trim(), 1, Integer::sum);
            }
        }

        String mostChallenged = null;
        int mostChallengedCount = 0;
        for (var entry : championWins.entrySet()) {
            if (entry.getValue() > mostChallengedCount) {
                mostChallenged = entry.getKey();
                mostChallengedCount = entry.getValue();
            }
        }

        totals.sort((a, b) -> Long.compare(b.value(), a.value()));
        List<MemberTotal> top = totals.stream().limit(TOP_LEADERBOARD_SIZE).toList();

        return new ClanOverviewStats(roster.size(), verifiedMembers, membersWithData, totalCombinedXp,
                totalXpGainedThisMonth, totalQuestsComplete, membersWithMaxedSkill, membersWith120Skill,
                top, mostChallenged, mostChallengedCount, timesCapped);
    }
}
