package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The clan points/promotion system: every day, awards points for clan membership and Citadel
 * visits/caps (see {@link ClanPointsRepository} for the award ledger and idempotency), then compares
 * each active member's running total against the guild's rank ladder ({@code clan_rank_config}) to
 * flag anyone whose actual in-game {@link ClanMemberRepository.ClanMemberRow#clanRank()} is behind
 * what their points have earned — those flagged members are what {@link TrackingGroup#CLAN_REPORT}
 * posts daily.
 * <p>
 * A member whose {@code clanRank} doesn't (case-insensitively) match any configured rank name is
 * skipped entirely for the promotion check — there's nothing to compare their points against — but
 * still gets their daily/Citadel points awarded normally.
 */
@BService
public class ClanPointsService {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d");

    private final ClanPointsRepository repository;
    private final ClanMemberRepository clanMemberRepository;
    private final WeeklyDigestRepository weeklyDigestRepository;
    private final TrackingEventRouter router;
    private final TrackingIconCatalog trackingIconCatalog;

    public ClanPointsService(ClanPointsRepository repository, ClanMemberRepository clanMemberRepository,
                              WeeklyDigestRepository weeklyDigestRepository, TrackingEventRouter router,
                              TrackingIconCatalog trackingIconCatalog) {
        this.repository = repository;
        this.clanMemberRepository = clanMemberRepository;
        this.weeklyDigestRepository = weeklyDigestRepository;
        this.router = router;
        this.trackingIconCatalog = trackingIconCatalog;
    }

    /**
     * Called once a day by {@code ClanPointsScheduler}, shortly after the daily clan sync has
     * refreshed everyone's {@code clan_rank}: awards yesterday's membership points, this Citadel
     * week's visit/cap points (idempotent either way — see {@link ClanPointsRepository#awardPoints}),
     * recomputes every active member's promotion-needed flag, then sends the Clan Report if anyone
     * needs one. Returns how many members the report listed (0 if nobody needed one, so nothing was
     * sent) — the scheduler ignores it; {@code /devclanreport} reports it back.
     */
    public int runDailyPointsAndPromotionCheck(Guild guild) {
        long guildId = guild.getIdLong();
        List<ClanMemberRepository.ClanMemberRow> roster = clanMemberRepository.getAll(guildId, true);
        if (roster.isEmpty()) return 0;

        ClanPointsRepository.PointsSettings settings = repository.getSettings(guildId);
        awardDailyMembershipPoints(guildId, roster, settings);
        awardCitadelPoints(guildId, roster, settings);
        recomputePromotionNeeded(guildId, roster);
        return sendClanReport(guild);
    }

    private void awardDailyMembershipPoints(long guildId, List<ClanMemberRepository.ClanMemberRow> roster, ClanPointsRepository.PointsSettings settings) {
        if (settings.dailyMembershipPoints() <= 0) return;

        // Credited for the day that just completed, not "today" — this job runs shortly after that
        // day's midnight UTC rollover.
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        for (var member : roster) {
            repository.awardPoints(guildId, member.rsn(), "DAILY_MEMBERSHIP", settings.dailyMembershipPoints(), yesterday);
        }
    }

    private void awardCitadelPoints(long guildId, List<ClanMemberRepository.ClanMemberRow> roster, ClanPointsRepository.PointsSettings settings) {
        if (settings.citadelVisitPoints() <= 0 && settings.citadelCapPoints() <= 0) return;

        OffsetDateTime[] window = currentWeeklyWindow();
        List<WeeklyDigestRepository.CitadelActivityRow> rows = weeklyDigestRepository.getCitadelActivityInWindow(guildId, window[0], window[1]);

        Set<String> visited = new HashSet<>();
        Set<String> capped = new HashSet<>();
        for (var row : rows) {
            String lower = row.rsn().toLowerCase(Locale.ROOT);
            if (row.activityText().startsWith("Visited")) visited.add(lower);
            else if (row.activityText().startsWith("Capped")) capped.add(lower);
        }

        // The window's own end date is a stable key for "this Citadel week" — every day this job
        // runs during the same week resolves to the same date, so awardPoints' unique index keeps
        // this to exactly one award per member per type per week no matter how many times it re-fires.
        LocalDate weekKey = window[1].toLocalDate();
        for (var member : roster) {
            String lower = member.rsn().toLowerCase(Locale.ROOT);
            if (settings.citadelVisitPoints() > 0 && visited.contains(lower)) {
                repository.awardPoints(guildId, member.rsn(), "CITADEL_VISIT", settings.citadelVisitPoints(), weekKey);
            }
            if (settings.citadelCapPoints() > 0 && capped.contains(lower)) {
                repository.awardPoints(guildId, member.rsn(), "CITADEL_CAP", settings.citadelCapPoints(), weekKey);
            }
        }
    }

    private void recomputePromotionNeeded(long guildId, List<ClanMemberRepository.ClanMemberRow> roster) {
        List<ClanPointsRepository.RankConfigRow> ranks = repository.getRanksOrdered(guildId);
        if (ranks.isEmpty()) return; // shouldn't happen — getRanksOrdered seeds defaults — but nothing to compare against if it somehow is

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (var member : roster) {
            Integer currentOrder = rankOrderFor(ranks, member.clanRank());
            if (currentOrder == null) continue; // this member's in-game rank string doesn't match any configured rank — can't compare

            long totalPoints = repository.getMemberPoints(guildId, member.rsn()).totalPoints();
            int earnedOrder = earnedRankOrder(ranks, totalPoints);
            repository.setPromotionNeeded(guildId, member.rsn(), earnedOrder > currentOrder, today);
        }
    }

    private static Integer rankOrderFor(List<ClanPointsRepository.RankConfigRow> ranks, String rankName) {
        for (var rank : ranks) {
            if (rank.rankName().equalsIgnoreCase(rankName)) return rank.rankOrder();
        }
        return null;
    }

    /** {@code ranks} must be ordered lowest to highest — the highest rank whose threshold {@code totalPoints} meets or exceeds. */
    private static int earnedRankOrder(List<ClanPointsRepository.RankConfigRow> ranks, long totalPoints) {
        int earned = ranks.getFirst().rankOrder();
        for (var rank : ranks) {
            if (totalPoints >= rank.pointThreshold()) earned = rank.rankOrder();
        }
        return earned;
    }

    /** Returns how many members the report listed — 0 means nothing was sent. */
    private int sendClanReport(Guild guild) {
        long guildId = guild.getIdLong();
        List<ClanPointsRepository.MemberPointsRow> flagged = repository.getAllNeedingPromotion(guildId);
        if (flagged.isEmpty()) return 0;

        List<ClanPointsRepository.RankConfigRow> ranks = repository.getRanksOrdered(guildId);
        Map<Integer, String> rankNameByOrder = new HashMap<>();
        for (var rank : ranks) rankNameByOrder.put(rank.rankOrder(), rank.rankName());

        Map<String, ClanMemberRepository.ClanMemberRow> rosterByRsnLower = new HashMap<>();
        for (var member : clanMemberRepository.getAll(guildId, true)) rosterByRsnLower.put(member.rsn().toLowerCase(Locale.ROOT), member);

        flagged.sort(Comparator.comparing(ClanPointsRepository.MemberPointsRow::promotionNeededSince,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### 🔺 Clan Report — Promotions Needed"));

        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (var row : flagged) {
            var member = rosterByRsnLower.get(row.rsn().toLowerCase(Locale.ROOT));
            if (member == null) continue; // left the clan since being flagged — nothing to report
            listed++;

            Integer currentOrder = rankOrderFor(ranks, member.clanRank());
            int earnedOrder = earnedRankOrder(ranks, row.totalPoints());
            String earnedRankName = rankNameByOrder.getOrDefault(earnedOrder, "?");
            String since = row.promotionNeededSince() != null ? row.promotionNeededSince().format(DISPLAY_DATE) : "?";

            // The current-rank icon sits directly against the player's name — that pairing *is* how
            // their current rank is shown, rather than also spelling the name out again in text right
            // next to it (two ranks' worth of icon+bold-name in one line read as clutter, not clarity).
            String currentBadge = currentOrder != null ? trackingIconCatalog.mentionForRank(currentOrder) : trackingIconCatalog.mentionForDefaultBoss();
            String earnedBadge = trackingIconCatalog.mentionForRank(earnedOrder);

            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(withIcon(currentBadge, "**" + member.rsn() + "**")).append('\n')
                    .append("Eligible for ").append(withIcon(earnedBadge, "**" + earnedRankName + "**"))
                    .append(" • ").append(row.totalPoints()).append(" pts • waiting since ").append(since);
        }

        if (sb.isEmpty()) return 0; // everyone flagged has since left the clan
        children.add(TextDisplay.of(sb.toString()));

        router.dispatchContainer(guild, TrackingGroup.CLAN_REPORT, Containers.card(Containers.WARNING, children));
        return listed;
    }

    private static String withIcon(String iconMention, String text) {
        return iconMention != null ? iconMention + " " + text : text;
    }

    /**
     * The current, still-in-progress Citadel week: from the last Wednesday 00:01 UTC reset through
     * the *next* Wednesday 00:00 UTC — unlike {@code WeeklyDigestScheduler}'s window (the most
     * recently *completed* week), this one's end date stays fixed for all 7 days of the week it
     * covers, which is exactly what makes it a stable idempotency key for {@link #awardCitadelPoints}:
     * every run during the same week resolves to the same {@code weekKey}, so a member awarded points
     * for visiting on day one of the week is never awarded again just because later runs still see
     * that same visit inside the window.
     */
    private static OffsetDateTime[] currentWeeklyWindow() {
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return new OffsetDateTime[]{windowEnd.minusDays(7).plusMinutes(1), windowEnd};
    }
}
