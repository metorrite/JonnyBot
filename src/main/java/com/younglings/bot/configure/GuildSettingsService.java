package com.younglings.bot.configure;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

/**
 * The read path every consumer (AdminRoleFilter, ClanSyncService, RsnRenameService,
 * VerificationRoleSyncService) should use instead of calling {@link BotConfig} directly for
 * anything that's now per-guild configurable — merges a guild's stored overrides with
 * {@code BotConfig}'s env vars, field by field, so a guild that's never touched {@code /configure}
 * keeps behaving exactly as it did when these were plain global settings.
 */
@BService
public class GuildSettingsService {
    private final GuildSettingsRepository repository;
    private final BotConfig botConfig;

    public GuildSettingsService(GuildSettingsRepository repository, BotConfig botConfig) {
        this.repository = repository;
        this.botConfig = botConfig;
    }

    public GuildSettings getEffective(long guildId) {
        GuildSettings stored = repository.get(guildId);

        String clanName = stored != null && stored.clanName() != null ? stored.clanName() : botConfig.getClanName();
        Long adminRoleId = stored != null && stored.adminRoleId() != null ? stored.adminRoleId() : botConfig.getAdminRoleId();
        Long renameAlertChannelId = stored != null && stored.renameAlertChannelId() != null
                ? stored.renameAlertChannelId() : botConfig.getRenameAlertChannelId();
        Long verificationReviewChannelId = stored != null && stored.verificationReviewChannelId() != null
                ? stored.verificationReviewChannelId() : botConfig.getVerificationReviewChannelId();
        Long verifiedClanRoleId = stored != null && stored.verifiedClanRoleId() != null
                ? stored.verifiedClanRoleId() : botConfig.getVerifiedClanRoleId();
        Long verifiedNonClanRoleId = stored != null && stored.verifiedNonClanRoleId() != null
                ? stored.verifiedNonClanRoleId() : botConfig.getVerifiedNonClanRoleId();
        Long unverifiedRoleId = stored != null && stored.unverifiedRoleId() != null
                ? stored.unverifiedRoleId() : botConfig.getUnverifiedRoleId();

        return new GuildSettings(guildId, clanName, adminRoleId, renameAlertChannelId, verificationReviewChannelId,
                verifiedClanRoleId, verifiedNonClanRoleId, unverifiedRoleId);
    }

    /** {@code null} for any field clears that guild's override, falling back to {@code BotConfig} again. */
    public void updateClanSettings(long guildId, String clanName, Long adminRoleId) {
        repository.upsertClanSettings(guildId, clanName, adminRoleId);
    }

    /** {@code null} clears the override, falling back to {@code BotConfig} again. */
    public void updateRenameAlertChannel(long guildId, Long renameAlertChannelId) {
        repository.upsertRenameAlertChannel(guildId, renameAlertChannelId);
    }

    /** {@code null} clears the override, falling back to {@code BotConfig} again. */
    public void updateVerificationSettings(long guildId, Long verificationReviewChannelId) {
        repository.upsertVerificationSettings(guildId, verificationReviewChannelId);
    }

    /** {@code null} for any field clears that role's override, falling back to {@code BotConfig} (or "no role") again. */
    public void updateVerificationRoleSettings(long guildId, Long verifiedClanRoleId, Long verifiedNonClanRoleId, Long unverifiedRoleId) {
        repository.upsertVerificationRoleSettings(guildId, verifiedClanRoleId, verifiedNonClanRoleId, unverifiedRoleId);
    }
}
