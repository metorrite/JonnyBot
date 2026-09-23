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

    /** Fetches the player's current profile and saves a snapshot of it. Empty if the profile couldn't be fetched (private, doesn't exist, or the request failed). */
    public Optional<RuneScapeProfile> pollAndSnapshot(long guildId, String rsn) {
        Optional<RuneScapeProfile> profile = apiClient.fetchProfile(rsn);
        profile.ifPresent(p -> repository.saveSnapshot(guildId, rsn, p, serializeSkills(p.skills())));
        return profile;
    }

    public PlayerLinkRepository.StatsSnapshotRow getLatestSnapshot(long guildId, String rsn) {
        return repository.getLatestSnapshot(guildId, rsn);
    }

    private String serializeSkills(List<SkillValue> skills) {
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
