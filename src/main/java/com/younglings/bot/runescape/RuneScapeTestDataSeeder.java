package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a synthetic 30-day poll history for a linked RSN, purely so the XP-over-time chart has
 * something to show while real history is still sparse (polling is manual — see
 * {@link com.younglings.bot.config.BotConfig#getRunescapeAutoPollEnabled}). Backdated snapshots are
 * anchored to end just before the player's actual latest real snapshot (which is left untouched) and
 * work backward with a fixed-per-skill daily XP gain, so the trend looks like steady grinding rather
 * than noise with no shape. Dev-only — reached from {@code RsAdminInteractionListener}'s "Seed Test
 * Data" button.
 */
@BService
public class RuneScapeTestDataSeeder {
    private static final int DAYS = 30;

    private final PlayerLinkRepository repository;

    public RuneScapeTestDataSeeder(PlayerLinkRepository repository) {
        this.repository = repository;
    }

    /** Number of backdated snapshots created, or 0 if this RSN has no real snapshot yet to anchor the trend to. */
    public int seed(long guildId, String rsn) {
        PlayerLinkRepository.StatsSnapshotRow latest = repository.getLatestSnapshot(guildId, rsn);
        if (latest == null) return 0;

        List<SkillValue> currentSkills = repository.getSkillsForSnapshot(latest.snapshotId());
        if (currentSkills.isEmpty()) return 0;

        Random random = new Random();
        Map<Integer, Long> dailyGain = new HashMap<>();
        for (SkillValue skill : currentSkills) {
            dailyGain.put(skill.skillId(), 20_000L + random.nextInt(280_000));
        }

        OffsetDateTime now = OffsetDateTime.now();
        int created = 0;
        for (int daysAgo = DAYS; daysAgo >= 1; daysAgo--) {
            OffsetDateTime at = now.minusDays(daysAgo).minusMinutes(random.nextInt(120));

            List<SkillValue> historicalSkills = new ArrayList<>();
            long totalXp = 0;
            // Total level is the real sum of all skill levels, same as the game's own "total level" —
            // held at today's levels rather than recomputed from the lowered XP, since only the XP
            // trend line matters for this feature; it's clearly synthetic data either way.
            int totalLevel = 0;
            for (SkillValue skill : currentSkills) {
                long backOff = dailyGain.get(skill.skillId()) * daysAgo;
                long xp = Math.max(0, skill.xp() - backOff);
                historicalSkills.add(new SkillValue(skill.skillId(), skill.level(), xp, skill.rank()));
                totalXp += xp;
                totalLevel += skill.level();
            }

            RuneScapeProfile fakeProfile = new RuneScapeProfile(rsn, totalLevel, totalXp, latest.combatLevel(),
                    latest.questsComplete(), latest.questsStarted(), latest.questsNotStarted(), historicalSkills, List.of());

            long snapshotId = repository.saveSnapshotAt(guildId, rsn, at, fakeProfile, RuneScapeStatsService.serializeSkills(historicalSkills));
            repository.saveSkillSnapshot(snapshotId, historicalSkills);
            created++;
        }
        return created;
    }
}
