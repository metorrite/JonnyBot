package com.younglings.bot.configure;

/**
 * One guild's resolved settings — already merged with {@code BotConfig}'s env-var defaults by
 * {@link GuildSettingsService#getEffective}, so every consumer reads this instead of choosing
 * between a per-guild override and a global fallback itself. Any field can still be {@code null} if
 * neither the guild nor the environment has configured it.
 * <p>
 * {@code clanName} is already {@code null} while clan features are switched off ({@code clanEnabled}
 * false), so every existing "no clan configured -> skip" check doubles as the on/off switch;
 * {@code savedClanName} is the name itself regardless, for the Clan Setup panel to keep showing.
 */
public record GuildSettings(long guildId, String clanName, Long adminRoleId, Long renameAlertChannelId,
                             Long verificationReviewChannelId, Long verifiedClanRoleId,
                             Long verifiedNonClanRoleId, Long unverifiedRoleId, Long onboardingRoleId,
                             boolean clanEnabled, String savedClanName, Long supportRoleId) {
    /** True only if a clan name is set <em>and</em> clan features haven't been switched off — what gates every clan-specific button and job. */
    public boolean clanActive() {
        return clanName != null;
    }
}
