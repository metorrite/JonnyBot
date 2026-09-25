package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gathers the numbers behind one player's month-to-date recap image ({@link MonthlyRecapRenderer}
 * draws it). Two of these are honest best-effort guesses rather than documented facts:
 * <ul>
 *     <li>{@code timesCapped} — activity entries whose text contains "capped" (case-insensitive).
 *     RuneMetrics doesn't distinguish a skill XP cap from, say, a Clan Citadel resource cap reaching
 *     its weekly limit ("Capped at my Clan Citadel") — both just say "capped", so both get counted.</li>
 *     <li>{@code mostChallenged} — activity entries matching "Won a challenge against the X
 *     Champion", tallied by X. This is the one boss-adjacent phrasing observed in real activity
 *     data this session; RuneMetrics almost certainly has other phrasings for other kinds of kills
 *     that aren't accounted for here.</li>
 * </ul>
 */
@BService
public class MonthlyRecapService {
    private static final Pattern CHAMPION_WIN = Pattern.compile(
            "Won a challenge against the (.+?) Champion", Pattern.CASE_INSENSITIVE);

    private final PlayerLinkRepository repository;

    public MonthlyRecapService(PlayerLinkRepository repository) {
        this.repository = repository;
    }

    /** {@code null} if this RSN has no snapshot at all yet (nothing to build a recap from). */
    public MonthlyRecapStats getStats(long guildId, String rsn) {
        OffsetDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.now();

        List<PlayerLinkRepository.StatsSnapshotRow> snapshotsThisMonth = repository.getSnapshotsSince(guildId, rsn, monthStart);
        if (snapshotsThisMonth.isEmpty()) return null;

        long totalXpGained = snapshotsThisMonth.getLast().totalXp() - snapshotsThisMonth.getFirst().totalXp();

        Map<Integer, Long> skillXpGained = new HashMap<>();
        List<PlayerLinkRepository.SkillHistoryPoint> allSkillPoints = repository.getAllSkillsXpHistorySince(guildId, rsn, monthStart);
        int currentSkillId = -1;
        long firstXpThisSkill = 0;
        long lastXpThisSkill = 0;
        for (PlayerLinkRepository.SkillHistoryPoint point : allSkillPoints) {
            if (point.skillId() != currentSkillId) {
                if (currentSkillId != -1) {
                    skillXpGained.put(currentSkillId, Math.max(0, lastXpThisSkill - firstXpThisSkill));
                }
                currentSkillId = point.skillId();
                firstXpThisSkill = point.xp();
            }
            lastXpThisSkill = point.xp();
        }
        if (currentSkillId != -1) {
            skillXpGained.put(currentSkillId, Math.max(0, lastXpThisSkill - firstXpThisSkill));
        }

        List<PlayerActivity> activities = repository.getActivitiesSince(guildId, rsn, monthStart);
        int timesCapped = 0;
        Map<String, Integer> championWins = new HashMap<>();
        for (PlayerActivity activity : activities) {
            if (activity.text().toLowerCase(Locale.ROOT).contains("capped")) timesCapped++;

            Matcher matcher = CHAMPION_WIN.matcher(activity.text());
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                championWins.merge(name, 1, Integer::sum);
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

        String monthLabel = LocalDate.now().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + LocalDate.now().getYear();

        return new MonthlyRecapStats(rsn, monthLabel, monthStart, now, totalXpGained, timesCapped,
                mostChallenged, mostChallengedCount, skillXpGained);
    }
}
