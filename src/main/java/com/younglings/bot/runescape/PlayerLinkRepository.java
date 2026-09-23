package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@BService
public class PlayerLinkRepository {
    private static final Logger log = LoggerFactory.getLogger(PlayerLinkRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public PlayerLinkRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static VerificationAttempt mapAttempt(ResultSet rs) throws SQLException {
        return new VerificationAttempt(
                rs.getLong("attempt_id"),
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("assigned_hairstyle"),
                rs.getString("assigned_hair_color"),
                rs.getString("assigned_skin_tone"),
                rs.getString("status")
        );
    }

    private static PlayerLink mapLink(ResultSet rs) throws SQLException {
        return new PlayerLink(
                rs.getLong("link_id"),
                rs.getLong("guild_id"),
                rs.getLong("discord_user_id"),
                rs.getString("rsn"),
                rs.getString("verification_method")
        );
    }

    // --- Verification attempts ---

    public long createAttempt(long guildId, long discordUserId, String rsn,
                               String hairstyle, String hairColor, String skinTone) {
        String sql = """
                INSERT INTO younglings.player_verification_attempt
                    (guild_id, discord_user_id, rsn, assigned_hairstyle, assigned_hair_color, assigned_skin_tone)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setString(3, rsn);
            statement.setString(4, hairstyle);
            statement.setString(5, hairColor);
            statement.setString(6, skinTone);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }

            throw new SQLException("No attempt_id returned after creating verification attempt.");

        } catch (SQLException e) {
            log.error("Failed to create verification attempt for '{}' (guild {})", rsn, guildId, e);
            throw new RuntimeException("Failed to create verification attempt", e);
        }
    }

    public VerificationAttempt getAttempt(long attemptId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE attempt_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, attemptId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapAttempt(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to get verification attempt", e);
        }
    }

    public void resolveAttempt(long attemptId, String status, long resolvedByUserId) {
        String sql = """
                UPDATE younglings.player_verification_attempt
                SET status = ?, resolved_at = NOW(), resolved_by_user_id = ?
                WHERE attempt_id = ?
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status);
            statement.setLong(2, resolvedByUserId);
            statement.setLong(3, attemptId);
            statement.executeUpdate();

            log.info("Resolved verification attempt {} as {} (by {})", attemptId, status, resolvedByUserId);

        } catch (SQLException e) {
            log.error("Failed to resolve verification attempt {}", attemptId, e);
            throw new RuntimeException("Failed to resolve verification attempt", e);
        }
    }

    public List<VerificationAttempt> getPendingAttempts(long guildId) {
        String sql = """
                SELECT attempt_id, guild_id, discord_user_id, rsn, assigned_hairstyle,
                       assigned_hair_color, assigned_skin_tone, status
                FROM younglings.player_verification_attempt
                WHERE guild_id = ? AND status = 'PENDING'
                ORDER BY requested_at ASC
                """;

        List<VerificationAttempt> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapAttempt(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get pending verification attempts for guild {}", guildId, e);
            throw new RuntimeException("Failed to get pending verification attempts", e);
        }
    }

    // --- Confirmed links ---

    public void createLink(long guildId, long discordUserId, String rsn, String verificationMethod) {
        String sql = """
                INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (guild_id, LOWER(rsn)) DO UPDATE SET
                    discord_user_id = EXCLUDED.discord_user_id,
                    verification_method = EXCLUDED.verification_method,
                    verified_at = NOW()
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);
            statement.setString(3, rsn);
            statement.setString(4, verificationMethod);
            statement.executeUpdate();

            log.info("Linked RSN '{}' to Discord user {} in guild {}", rsn, discordUserId, guildId);

        } catch (SQLException e) {
            log.error("Failed to link RSN '{}' to user {}", rsn, discordUserId, e);
            throw new RuntimeException("Failed to link RSN", e);
        }
    }

    public List<PlayerLink> getLinksForUser(long guildId, long discordUserId) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method
                FROM younglings.player_link
                WHERE guild_id = ? AND discord_user_id = ?
                ORDER BY verified_at ASC
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, discordUserId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapLink(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get links for user {}", discordUserId, e);
            throw new RuntimeException("Failed to get player links", e);
        }
    }

    public PlayerLink getLinkForRsn(long guildId, String rsn) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method
                FROM younglings.player_link
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? mapLink(rs) : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get link for RSN '{}'", rsn, e);
            throw new RuntimeException("Failed to get player link", e);
        }
    }

    public void deleteLink(long guildId, long linkId) {
        String sql = "DELETE FROM younglings.player_link WHERE guild_id = ? AND link_id = ?";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setLong(2, linkId);
            statement.executeUpdate();

            log.info("Unlinked player_link {} in guild {}", linkId, guildId);

        } catch (SQLException e) {
            log.error("Failed to unlink {}", linkId, e);
            throw new RuntimeException("Failed to unlink RSN", e);
        }
    }

    /** All currently-linked RSNs across the guild, for the stats scheduler to poll. */
    public List<PlayerLink> getAllLinks(long guildId) {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method
                FROM younglings.player_link
                WHERE guild_id = ?
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(mapLink(rs));
            }

            return results;

        } catch (SQLException e) {
            log.error("Failed to get all player links for guild {}", guildId, e);
            throw new RuntimeException("Failed to get all player links", e);
        }
    }

    /** Every confirmed link across every guild — used by the stats scheduler, which isn't scoped to one guild. */
    public List<PlayerLink> getAllLinksAcrossGuilds() {
        String sql = """
                SELECT link_id, guild_id, discord_user_id, rsn, verification_method
                FROM younglings.player_link
                """;

        List<PlayerLink> results = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            while (rs.next()) results.add(mapLink(rs));
            return results;

        } catch (SQLException e) {
            log.error("Failed to get all player links across guilds", e);
            throw new RuntimeException("Failed to get all player links", e);
        }
    }

    // --- Stats snapshots ---

    public void saveSnapshot(long guildId, String rsn, RuneScapeProfile profile, String skillsJson) {
        String sql = """
                INSERT INTO younglings.player_stats_snapshot
                    (rsn, guild_id, total_level, total_xp, combat_level, quests_complete, skills_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, rsn);
            statement.setLong(2, guildId);
            statement.setInt(3, profile.totalLevel());
            statement.setLong(4, profile.totalXp());
            statement.setInt(5, profile.combatLevel());
            statement.setInt(6, profile.questsComplete());
            statement.setString(7, skillsJson);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to save stats snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to save stats snapshot", e);
        }
    }

    /** Most recent snapshot for {@code rsn}, or {@code null} if it's never been polled. */
    public StatsSnapshotRow getLatestSnapshot(long guildId, String rsn) {
        String sql = """
                SELECT snapshot_at, total_level, total_xp, combat_level, quests_complete
                FROM younglings.player_stats_snapshot
                WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)
                ORDER BY snapshot_at DESC
                LIMIT 1
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new StatsSnapshotRow(
                        rs.getObject("snapshot_at", java.time.OffsetDateTime.class),
                        rs.getInt("total_level"),
                        rs.getLong("total_xp"),
                        rs.getInt("combat_level"),
                        rs.getInt("quests_complete")
                );
            }

        } catch (SQLException e) {
            log.error("Failed to get latest snapshot for '{}'", rsn, e);
            throw new RuntimeException("Failed to get latest stats snapshot", e);
        }
    }

    public record StatsSnapshotRow(java.time.OffsetDateTime snapshotAt, int totalLevel, long totalXp,
                                    int combatLevel, int questsComplete) {
    }
}
