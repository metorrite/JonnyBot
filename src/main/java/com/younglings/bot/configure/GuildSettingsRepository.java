package com.younglings.bot.configure;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

@BService
public class GuildSettingsRepository {
    private static final Logger log = LoggerFactory.getLogger(GuildSettingsRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public GuildSettingsRepository(ConnectionSupplier connectionSupplier, GuildSettingsDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** {@code null} if this guild has never had any setting configured — every field falls back to {@code BotConfig} in that case. */
    public GuildSettings get(long guildId) {
        String sql = """
                SELECT guild_id, clan_name, admin_role_id, rename_alert_channel_id, verification_review_channel_id,
                       verified_clan_role_id, verified_non_clan_role_id, unverified_role_id, onboarding_role_id,
                       clan_enabled, support_role_id, developer_role_id
                FROM younglings.guild_settings WHERE guild_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new GuildSettings(
                        rs.getLong("guild_id"),
                        rs.getString("clan_name"),
                        (Long) rs.getObject("admin_role_id"),
                        (Long) rs.getObject("rename_alert_channel_id"),
                        (Long) rs.getObject("verification_review_channel_id"),
                        (Long) rs.getObject("verified_clan_role_id"),
                        (Long) rs.getObject("verified_non_clan_role_id"),
                        (Long) rs.getObject("unverified_role_id"),
                        (Long) rs.getObject("onboarding_role_id"),
                        rs.getBoolean("clan_enabled"),
                        rs.getString("clan_name"),
                        (Long) rs.getObject("support_role_id"),
                        (Long) rs.getObject("developer_role_id"));
            }

        } catch (SQLException e) {
            log.error("Failed to get guild settings for {}", guildId, e);
            throw new RuntimeException("Failed to get guild settings", e);
        }
    }

    /** Any parameter may be {@code null} to clear that override (falling back to {@code BotConfig} again). */
    public void upsertClanSettings(long guildId, String clanName, Long adminRoleId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, clan_name, admin_role_id)
                VALUES (?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    clan_name = EXCLUDED.clan_name,
                    admin_role_id = EXCLUDED.admin_role_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, clanName);
            setNullableLong(statement, 3, adminRoleId);
            statement.executeUpdate();

            log.info("Updated clan settings for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert guild settings for {}", guildId, e);
            throw new RuntimeException("Failed to upsert guild settings", e);
        }
    }

    /** A narrower upsert than {@link #upsertClanSettings} — only ever touches this one column, picked via a native channel dropdown now instead of a typed/pasted ID. */
    public void upsertRenameAlertChannel(long guildId, Long renameAlertChannelId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, rename_alert_channel_id)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    rename_alert_channel_id = EXCLUDED.rename_alert_channel_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, renameAlertChannelId);
            statement.executeUpdate();

            log.info("Updated rename alert channel for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert rename alert channel for {}", guildId, e);
            throw new RuntimeException("Failed to upsert rename alert channel", e);
        }
    }

    /**
     * A narrower upsert than {@link #upsertClanSettings} — only ever touches these three columns.
     * Each of the Verification panel's three role dropdowns calls this with the other two values
     * unchanged (read from {@link GuildSettingsService#getEffective} first), so picking one role
     * never clobbers the other two.
     */
    public void upsertVerificationRoleSettings(long guildId, Long verifiedClanRoleId, Long verifiedNonClanRoleId, Long unverifiedRoleId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, verified_clan_role_id, verified_non_clan_role_id, unverified_role_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    verified_clan_role_id = EXCLUDED.verified_clan_role_id,
                    verified_non_clan_role_id = EXCLUDED.verified_non_clan_role_id,
                    unverified_role_id = EXCLUDED.unverified_role_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, verifiedClanRoleId);
            setNullableLong(statement, 3, verifiedNonClanRoleId);
            setNullableLong(statement, 4, unverifiedRoleId);
            statement.executeUpdate();

            log.info("Updated verification role settings for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert verification role settings for {}", guildId, e);
            throw new RuntimeException("Failed to upsert verification role settings", e);
        }
    }

    /**
     * A narrower upsert than {@link #upsertClanSettings} — only ever touches this one column, so a
     * guild that's only ever set its Verification settings doesn't have its (unrelated) clan name,
     * admin role, etc. wiped back to {@code null} by a modal that never asked about them.
     */
    public void upsertVerificationSettings(long guildId, Long verificationReviewChannelId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, verification_review_channel_id)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    verification_review_channel_id = EXCLUDED.verification_review_channel_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, verificationReviewChannelId);
            statement.executeUpdate();

            log.info("Updated verification settings for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert verification settings for {}", guildId, e);
            throw new RuntimeException("Failed to upsert verification settings", e);
        }
    }

    /**
     * A narrower upsert than {@link #upsertClanSettings} — only ever touches this one column. Separate
     * from {@link #upsertVerificationRoleSettings} since this role applies at a different moment
     * (submission, not approval) and is edited from its own dropdown.
     */
    public void upsertOnboardingRole(long guildId, Long onboardingRoleId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, onboarding_role_id)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    onboarding_role_id = EXCLUDED.onboarding_role_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, onboardingRoleId);
            statement.executeUpdate();

            log.info("Updated onboarding role for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert onboarding role for {}", guildId, e);
            throw new RuntimeException("Failed to upsert onboarding role", e);
        }
    }

    /** A narrower upsert than {@link #upsertClanSettings} — only the clan name. */
    public void upsertClanName(long guildId, String clanName) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, clan_name)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    clan_name = EXCLUDED.clan_name
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, clanName);
            statement.executeUpdate();

            log.info("Updated clan name for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert clan name for {}", guildId, e);
            throw new RuntimeException("Failed to upsert clan name", e);
        }
    }

    /** A narrower upsert than {@link #upsertClanSettings} — only the bot's Admin role. */
    public void upsertAdminRole(long guildId, Long adminRoleId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, admin_role_id)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    admin_role_id = EXCLUDED.admin_role_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, adminRoleId);
            statement.executeUpdate();

            log.info("Updated admin role for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert admin role for {}", guildId, e);
            throw new RuntimeException("Failed to upsert admin role", e);
        }
    }

    /** A narrower upsert than {@link #upsertClanSettings} — only the Support role. */
    public void upsertSupportRole(long guildId, Long supportRoleId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, support_role_id)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    support_role_id = EXCLUDED.support_role_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, supportRoleId);
            statement.executeUpdate();

            log.info("Updated support role for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert support role for {}", guildId, e);
            throw new RuntimeException("Failed to upsert support role", e);
        }
    }

    /** A narrower upsert than {@link #upsertClanSettings} — only the Developer role. */
    public void upsertDeveloperRole(long guildId, Long developerRoleId) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, developer_role_id)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    developer_role_id = EXCLUDED.developer_role_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            setNullableLong(statement, 2, developerRoleId);
            statement.executeUpdate();

            log.info("Updated developer role for guild {}", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert developer role for {}", guildId, e);
            throw new RuntimeException("Failed to upsert developer role", e);
        }
    }

    /** The master switch for every clan-specific feature — see {@link GuildSettings#clanEnabled()}. */
    public void upsertClanEnabled(long guildId, boolean enabled) {
        String sql = """
                INSERT INTO younglings.guild_settings (guild_id, clan_enabled)
                VALUES (?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET
                    clan_enabled = EXCLUDED.clan_enabled
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setBoolean(2, enabled);
            statement.executeUpdate();

            log.info("Set clan features {} for guild {}", enabled ? "enabled" : "disabled", guildId);

        } catch (SQLException e) {
            log.error("Failed to upsert clan enabled for {}", guildId, e);
            throw new RuntimeException("Failed to upsert clan enabled", e);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }
}
