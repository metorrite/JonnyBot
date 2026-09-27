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
     * request failed) — nothing is saved in that case. See {@link #pollAndSnapshotResult} to tell
     * those failure reasons apart.
     */
    public Optional<RuneScapeProfile> pollAndSnapshot(long guildId, String rsn) {
        return pollAndSnapshotResult(guildId, rsn) instanceof ProfileResult.Found(var profile)
                ? Optional.of(profile) : Optional.empty();
    }

    /**
     * Same fetch-and-save as {@link #pollAndSnapshot}, but keeps the reason a failure happened
     * instead of collapsing it to empty. Always writes a fresh snapshot on a successful fetch, even
     * if nothing about the player changed since last time — a database write here is cheap, and
     * keeping "polled X ago" accurate and the code simple is worth more than skipping an unchanged
     * write. (An earlier version of this method kept an in-memory copy of each player's last-known
     * stats specifically to skip that write; that traded a negligible amount of database traffic for
     * held-in-memory state, the wrong side of that tradeoff for this app — see the memory/cost report
     * from 2026-09-26.)
     */
    public ProfileResult pollAndSnapshotResult(long guildId, String rsn) {
        ProfileResult result = apiClient.fetchProfileResult(rsn);
        if (result instanceof ProfileResult.Found(var profile)) {
            long snapshotId = repository.saveSnapshot(guildId, rsn, profile, serializeSkills(profile.skills()));
            repository.saveSkillSnapshot(snapshotId, profile.skills());
            repository.saveActivities(guildId, rsn, profile.activities());
        }
        return result;
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
