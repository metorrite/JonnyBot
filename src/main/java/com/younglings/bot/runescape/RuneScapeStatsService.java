package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@BService
public class RuneScapeStatsService {
    private final RuneScapeApiClient apiClient;
    private final PlayerLinkRepository repository;

    // Last-known stats per (guild, rsn), so a poll that comes back completely unchanged skips the
    // full write cascade below (see pollAndSnapshotResult) instead of comparing against the database
    // every single time — that comparison read is exactly the kind of extra database access this
    // cache is meant to avoid. Reset on restart, but that only costs one extra database read (not a
    // write) per player on whichever poll happens to be first after a restart — see
    // loadLastKnownFromDb. Not scoped per-guild in its own nested map since guildId is already part
    // of the key string.
    private final Map<String, CachedProfile> lastKnownByKey = new ConcurrentHashMap<>();
    private final Map<String, Long> latestSnapshotIdByKey = new ConcurrentHashMap<>();

    public RuneScapeStatsService(RuneScapeApiClient apiClient, PlayerLinkRepository repository) {
        this.apiClient = apiClient;
        this.repository = repository;
    }

    /**
     * Fetches the player's current profile and saves a snapshot of it — the per-skill breakdown
     * and any new activities go into their own tables (see {@link PlayerLinkRepository}), not just
     * the summary row. Empty if the profile couldn't be fetched (private, doesn't exist, or the
     * request failed) — nothing is saved in that case. See {@link #pollAndSnapshotResult} to tell
     * those failure reasons apart.
     */
    public Optional<RuneScapeProfile> pollAndSnapshot(long guildId, String rsn) {
        return pollAndSnapshotResult(guildId, rsn) instanceof ProfileResult.Found(var profile)
                ? Optional.of(profile) : Optional.empty();
    }

    /**
     * Same fetch-and-save as {@link #pollAndSnapshot}, but keeps the reason a failure happened
     * instead of collapsing it to empty.
     * <p>
     * A successful fetch is compared (level/xp/quests/per-skill xp, plus activities — <em>not</em>
     * hiscore rank, which can drift on its own from other players' progress with nothing about this
     * player changing at all) against the last known copy, in memory first and the database only on
     * a cache miss. Genuinely nothing different skips the full insert cascade (a snapshot row, up to
     * 29 skill rows, and any activity upserts) in favor of a single-column touch of the existing
     * snapshot's timestamp, so "polled X ago" still reflects reality without paying for the rest.
     */
    public ProfileResult pollAndSnapshotResult(long guildId, String rsn) {
        ProfileResult result = apiClient.fetchProfileResult(rsn);
        if (result instanceof ProfileResult.Found(var profile)) {
            String cacheKey = guildId + "|" + rsn.toLowerCase();
            CachedProfile fresh = CachedProfile.of(profile);
            CachedProfile lastKnown = lastKnownByKey.get(cacheKey);
            if (lastKnown == null) lastKnown = loadLastKnownFromDb(guildId, rsn, cacheKey, profile.activities().size());

            if (fresh.equals(lastKnown)) {
                Long snapshotId = latestSnapshotIdByKey.get(cacheKey);
                if (snapshotId != null) repository.touchSnapshot(snapshotId);
            } else {
                long snapshotId = repository.saveSnapshot(guildId, rsn, profile, serializeSkills(profile.skills()));
                repository.saveSkillSnapshot(snapshotId, profile.skills());
                repository.saveActivities(guildId, rsn, profile.activities());
                latestSnapshotIdByKey.put(cacheKey, snapshotId);
            }
            lastKnownByKey.put(cacheKey, fresh);
        }
        return result;
    }

    /** Cache-miss fallback (first poll for this player since a restart): one database read to seed the comparison, instead of always writing blind. {@code null} if this player's truly never been polled before. */
    private CachedProfile loadLastKnownFromDb(long guildId, String rsn, String cacheKey, int freshActivityCount) {
        var latest = repository.getLatestSnapshot(guildId, rsn);
        if (latest == null) return null;

        latestSnapshotIdByKey.put(cacheKey, latest.snapshotId());
        List<SkillValue> skills = repository.getSkillsForSnapshot(latest.snapshotId());
        List<PlayerActivity> activities = repository.getRecentActivities(guildId, rsn, freshActivityCount);
        return CachedProfile.fromSnapshot(latest, skills, activities);
    }

    private record SkillLevelXp(int skillId, int level, long xp) {}

    /** Everything about a poll that reflects the player's own progress — deliberately excludes hiscore rank (see {@link #pollAndSnapshotResult}). */
    private record CachedProfile(int totalLevel, long totalXp, int combatLevel, int questsComplete,
                                  int questsStarted, int questsNotStarted,
                                  Set<SkillLevelXp> skills, Set<PlayerActivity> activities) {
        static CachedProfile of(RuneScapeProfile profile) {
            return new CachedProfile(profile.totalLevel(), profile.totalXp(), profile.combatLevel(),
                    profile.questsComplete(), profile.questsStarted(), profile.questsNotStarted(),
                    skillSet(profile.skills()), Set.copyOf(profile.activities()));
        }

        static CachedProfile fromSnapshot(PlayerLinkRepository.StatsSnapshotRow row, List<SkillValue> skills, List<PlayerActivity> activities) {
            return new CachedProfile(row.totalLevel(), row.totalXp(), row.combatLevel(),
                    row.questsComplete(), row.questsStarted(), row.questsNotStarted(),
                    skillSet(skills), Set.copyOf(activities));
        }

        private static Set<SkillLevelXp> skillSet(List<SkillValue> skills) {
            return skills.stream().map(s -> new SkillLevelXp(s.skillId(), s.level(), s.xp())).collect(Collectors.toUnmodifiableSet());
        }
    }

    public PlayerLinkRepository.StatsSnapshotRow getLatestSnapshot(long guildId, String rsn) {
        return repository.getLatestSnapshot(guildId, rsn);
    }

    /** Snapshot history, most recent first — the data source for a "gains over time" view. */
    public List<PlayerLinkRepository.StatsSnapshotRow> getSnapshotHistory(long guildId, String rsn, int limit) {
        return repository.getSnapshotHistory(guildId, rsn, limit);
    }

    public List<SkillValue> getSkillsForSnapshot(long snapshotId) {
        return repository.getSkillsForSnapshot(snapshotId);
    }

    public List<PlayerActivity> getRecentActivities(long guildId, String rsn, int limit) {
        return repository.getRecentActivities(guildId, rsn, limit);
    }

    /** One skill's XP at each poll over the last {@code days} days, oldest first — the XP chart's data source. */
    public List<SkillXpPoint> getSkillXpHistory(long guildId, String rsn, int skillId, int days) {
        return repository.getSkillXpHistory(guildId, rsn, skillId, java.time.OffsetDateTime.now().minusDays(days));
    }

    /** Every snapshot since {@code since}, oldest first. */
    public List<PlayerLinkRepository.StatsSnapshotRow> getSnapshotsSince(long guildId, String rsn, java.time.OffsetDateTime since) {
        return repository.getSnapshotsSince(guildId, rsn, since);
    }

    /** Activities recorded since {@code since}, oldest first. */
    public List<PlayerActivity> getActivitiesSince(long guildId, String rsn, java.time.OffsetDateTime since) {
        return repository.getActivitiesSince(guildId, rsn, since);
    }

    /** Every skill's XP at every poll since {@code since} in one query — the stacked-bar chart's data source. */
    public List<PlayerLinkRepository.SkillHistoryPoint> getAllSkillsXpHistorySince(long guildId, String rsn, java.time.OffsetDateTime since) {
        return repository.getAllSkillsXpHistorySince(guildId, rsn, since);
    }

    static String serializeSkills(List<SkillValue> skills) {
        DataArray array = DataArray.empty();
        for (SkillValue skill : skills) {
            array.add(DataObject.empty()
                    .put("id", skill.skillId())
                    .put("level", skill.level())
                    .put("xp", skill.xp())
                    .put("rank", skill.rank()));
        }
        return array.toString();
    }
}
