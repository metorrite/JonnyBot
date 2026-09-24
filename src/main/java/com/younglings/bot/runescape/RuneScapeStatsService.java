package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.Optional;

@BService
public class RuneScapeStatsService {
    private final RuneScapeApiClient apiClient;
    private final PlayerLinkRepository repository;

    public RuneScapeStatsService(RuneScapeApiClient apiClient, PlayerLinkRepository repository) {
        this.apiClient = apiClient;
        this.repository = repository;
    }

    /**
     * Fetches the player's current profile and saves a snapshot of it — the per-skill breakdown
     * and any new activities go into their own tables (see {@link PlayerLinkRepository}), not just
     * the summary row. Empty if the profile couldn't be fetched (private, doesn't exist, or the
     * request failed) — nothing is saved in that case.
     */
    public Optional<RuneScapeProfile> pollAndSnapshot(long guildId, String rsn) {
        Optional<RuneScapeProfile> profile = apiClient.fetchProfile(rsn);
        profile.ifPresent(p -> {
            long snapshotId = repository.saveSnapshot(guildId, rsn, p, serializeSkills(p.skills()));
            repository.saveSkillSnapshot(snapshotId, p.skills());
            repository.saveActivities(guildId, rsn, p.activities());
        });
        return profile;
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
