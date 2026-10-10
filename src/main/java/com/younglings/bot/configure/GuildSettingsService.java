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

    /**
     * Whether {@code guildId} may fall back to the environment's settings. Those describe the bot's own home server
     * ({@code GUILD_ID}): a clan name and role and channel ids that mean nothing anywhere else, and that would hand a
     * newly installed server somebody else's clan. So any other server starts with nothing and has to be configured.
     * With no {@code GUILD_ID} at all (a single-server setup) every server counts as home, which is how it behaved before.
     */
    boolean usesEnvironmentDefaults(long guildId) {
        Long home = botConfig.getGuildId();
        return home == null || home == guildId;
    }

    public GuildSettings getEffective(long guildId) {
        GuildSettings stored = repository.get(guildId);
        boolean env = usesEnvironmentDefaults(guildId);

        String clanName = stored != null && stored.clanName() != null ? stored.clanName() : env ? botConfig.getClanName() : null;
        Long adminRoleId = stored != null && stored.adminRoleId() != null ? stored.adminRoleId() : env ? botConfig.getAdminRoleId() : null;
        Long renameAlertChannelId = stored != null && stored.renameAlertChannelId() != null
                ? stored.renameAlertChannelId() : env ? botConfig.getRenameAlertChannelId() : null;
        Long verificationReviewChannelId = stored != null && stored.verificationReviewChannelId() != null
                ? stored.verificationReviewChannelId() : env ? botConfig.getVerificationReviewChannelId() : null;
        Long verifiedClanRoleId = stored != null && stored.verifiedClanRoleId() != null
                ? stored.verifiedClanRoleId() : env ? botConfig.getVerifiedClanRoleId() : null;
        Long verifiedNonClanRoleId = stored != null && stored.verifiedNonClanRoleId() != null
                ? stored.verifiedNonClanRoleId() : env ? botConfig.getVerifiedNonClanRoleId() : null;
        Long unverifiedRoleId = stored != null && stored.unverifiedRoleId() != null
                ? stored.unverifiedRoleId() : env ? botConfig.getUnverifiedRoleId() : null;
        Long onboardingRoleId = stored != null ? stored.onboardingRoleId() : null;

        boolean clanEnabled = stored == null || stored.clanEnabled();

        return new GuildSettings(guildId, clanEnabled ? clanName : null, adminRoleId, renameAlertChannelId, verificationReviewChannelId,
                verifiedClanRoleId, verifiedNonClanRoleId, unverifiedRoleId, onboardingRoleId, clanEnabled, clanName,
                stored != null ? stored.supportRoleId() : null, stored != null ? stored.developerRoleId() : null,
                stored != null ? stored.websiteUrl() : null);
    }

    /** The clan's website, already cleaned by {@link WebsiteLink#normalize}; {@code null} clears it. */
    public void updateWebsiteUrl(long guildId, String websiteUrl) {
        repository.upsertWebsiteUrl(guildId, websiteUrl);
    }

    /** Only the clan name — {@code null} clears the override, falling back to {@code BotConfig} again. The caller is responsible for having verified the clan first. */
    public void updateClanName(long guildId, String clanName) {
        repository.upsertClanName(guildId, clanName);
    }

    /** Switches every clan-specific feature on or off for this guild without losing the saved clan name. */
    public void setClanEnabled(long guildId, boolean enabled) {
        repository.upsertClanEnabled(guildId, enabled);
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

    /** {@code null} clears the override — no role granted on submission. */
    public void updateOnboardingRole(long guildId, Long onboardingRoleId) {
        repository.upsertOnboardingRole(guildId, onboardingRoleId);
    }
}
