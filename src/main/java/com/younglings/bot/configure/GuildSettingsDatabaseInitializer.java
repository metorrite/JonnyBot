package com.younglings.bot.configure;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class GuildSettingsDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(GuildSettingsDatabaseInitializer.class);

    public GuildSettingsDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "guild_settings", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // Per-guild overrides for what used to be single global env vars (ADMIN_ROLE_ID,
                // RSN_RENAME_ALERT_CHANNEL_ID, VERIFIED_ROLE_NAME, UNVERIFIED_ROLE_NAME) plus the
                // clan name that used to be a hardcoded Java constant. Every column is nullable and
                // falls back to BotConfig's env var when unset — see
                // GuildSettingsService#getEffective — so the one guild this bot already runs in
                // keeps working with zero migration, while any other guild gets independently
                // configurable values via /configure.
                """
                CREATE TABLE IF NOT EXISTS younglings.guild_settings (
                    guild_id BIGINT PRIMARY KEY,
                    clan_name TEXT NULL,
                    admin_role_id BIGINT NULL,
                    rename_alert_channel_id BIGINT NULL,
                    verification_review_channel_id BIGINT NULL,
                    verified_clan_role_id BIGINT NULL,
                    verified_non_clan_role_id BIGINT NULL,
                    unverified_role_id BIGINT NULL
                );
                """,

                // Added after the table already existed live — see the identical pattern/reasoning
                // in RuneScapeDatabaseInitializer. Where RsInteractionListener posts a new RSN-link
                // request for an admin to Approve/Reject, now that submitting one no longer requires
                // the makeover-mage appearance dance first (temporarily disabled — see
                // PlayerLinkService).
                """
                ALTER TABLE younglings.guild_settings
                    ADD COLUMN IF NOT EXISTS verification_review_channel_id BIGINT NULL;
                """,

                // Replaces verified_role_name/unverified_role_name (name-based, picked by typing a
                // role's exact name into a modal) with three ID-based, clan-membership-aware slots,
                // each picked from a real dropdown of the guild's roles — see
                // ConfigureInteractionListener's Verification panel and
                // VerificationRoleSyncService. All three are optional (NULL = do nothing for that
                // slot); verified_non_clan_role_id in particular is meant for a role something else
                // (e.g. a join flow) usually already grants — this is only a supplemental assign for
                // a verified member the bot finds isn't in the clan.
                """
                ALTER TABLE younglings.guild_settings
                    ADD COLUMN IF NOT EXISTS verified_clan_role_id BIGINT NULL,
                    ADD COLUMN IF NOT EXISTS verified_non_clan_role_id BIGINT NULL,
                    ADD COLUMN IF NOT EXISTS unverified_role_id BIGINT NULL,
                    DROP COLUMN IF EXISTS verified_role_name,
                    DROP COLUMN IF EXISTS unverified_role_name;
                """,

                // onboarding_role_id: granted the moment someone submits /rs, before an admin has
                // approved anything — a separate, earlier role than verified_clan_role_id /
                // verified_non_clan_role_id / unverified_role_id above, which only apply once a
                // request is actually resolved.
                //
                // rules_channel_id/rules_message_id/rules_text used to live here too (the old
                // single-channel "post the rules as the bot" Configure panel) — they were migrated
                // into the generalized younglings.announcement_* tables and dropped from this table;
                // see AnnouncementDatabaseInitializer's one-time migration statement.
                """
                ALTER TABLE younglings.guild_settings
                    ADD COLUMN IF NOT EXISTS onboarding_role_id BIGINT NULL;
                """,

                // The master on/off switch for clan features (Clan Setup panel). Defaults TRUE so every
                // guild that already has a clan keeps working untouched; the saved clan name is kept
                // while it's off so switching back on needs no re-entry.
                """
                ALTER TABLE younglings.guild_settings
                    ADD COLUMN IF NOT EXISTS clan_enabled BOOLEAN NOT NULL DEFAULT TRUE;
                """,

                // The Support role: may review and verify RSN requests in /rsadmin and nothing else there.
                // No BotConfig fallback — unset simply means nobody has the Support tier.
                """
                ALTER TABLE younglings.guild_settings
                    ADD COLUMN IF NOT EXISTS support_role_id BIGINT NULL;
                """,

                // The Developer role: alongside the Admin tier, may open the website's admin dashboard.
                // No BotConfig fallback — unset simply means only the Admin tier gets in.
                """
                ALTER TABLE younglings.guild_settings
                    ADD COLUMN IF NOT EXISTS developer_role_id BIGINT NULL;
                """
        ));
    }
}
