package com.younglings.bot.configure;

/**
 * One guild's resolved settings — already merged with {@code BotConfig}'s env-var defaults by
 * {@link GuildSettingsService#getEffective}, so every consumer reads this instead of choosing
 * between a per-guild override and a global fallback itself. Any field can still be {@code null} if
 * neither the guild nor the environment has configured it.
 */
public record GuildSettings(long guildId, String clanName, Long adminRoleId, Long renameAlertChannelId,
                             Long verificationReviewChannelId, Long verifiedClanRoleId,
                             Long verifiedNonClanRoleId, Long unverifiedRoleId) {
}
