package com.younglings.bot.signup;

import com.younglings.bot.commands.signup.SignupEntry;
import com.younglings.bot.commands.signup.SignupMessage;
import com.younglings.bot.commands.signup.SignupSession;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@BService
public class SignupRepository {
    private static final Logger log = LoggerFactory.getLogger(SignupRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public SignupRepository(ConnectionSupplier connectionSupplier, SignupDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    public long createSignup(long guildId, String title, String notificationMessage, Integer maxSignups, long createdByUserId) {
        String sql = """
                INSERT INTO younglings.signup
                    (guild_id, title, notification_message, max_signups, created_by_user_id)
                VALUES (?, ?, ?, ?, ?)
                RETURNING signup_id
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, title);
            statement.setString(3, notificationMessage);

            if (maxSignups == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, maxSignups);
            }

            statement.setLong(5, createdByUserId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long signupId = resultSet.getLong("signup_id");
                    log.info("Created signup {} for guild {}", signupId, guildId);
                    return signupId;
                }
            }

            throw new SQLException("No signup_id returned after creating signup.");

        } catch (SQLException e) {
            log.error("Failed to create signup for guild {}", guildId, e);
            throw new RuntimeException("Failed to create signup", e);
        }
    }

    public List<SignupSession> getOpenSignups() {
        String sql = """
        SELECT signup_id, guild_id, title, notification_message, max_signups
        FROM younglings.signup
        WHERE deleted_at IS NULL
        ORDER BY created_at ASC
        """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                Integer maxSignups = resultSet.getObject("max_signups") == null
                        ? null
                        : resultSet.getInt("max_signups");

                signups.add(new SignupSession(
                        resultSet.getLong("signup_id"),
                        resultSet.getLong("guild_id"),
                        resultSet.getString("title"),
                        resultSet.getString("notification_message"),
                        maxSignups
                ));
            }

            log.info("Loaded {} open signups from database.", signups.size());
            return signups;

        } catch (SQLException e) {
            log.error("Failed to load open signups from database.", e);
            throw new RuntimeException("Failed to load open signups", e);
        }
    }

    public String getSignupStatus(long signupId) {
        String sql = """
            SELECT status
            FROM younglings.signup
            WHERE signup_id = ?
              AND deleted_at IS NULL
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }

        } catch (SQLException e) {
            log.error("Failed to get signup status for {}", signupId, e);
            throw new RuntimeException("Failed to get signup status", e);
        }
    }

    public void deleteSignupForAdminArchive(long signupId) {
        String sql = """
            UPDATE younglings.signup
            SET status = 'DELETED',
                deleted_at = NOW()
            WHERE signup_id = ?
            """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, signupId);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM younglings.signup_entry
                WHERE signup_id = ?
                """)) {
                statement.setLong(1, signupId);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE younglings.signup_message
                SET active = FALSE
                WHERE signup_id = ?
                  AND message_type = 'PUBLIC'
                """)) {
                statement.setLong(1, signupId);
                statement.executeUpdate();
            }

            connection.commit();

            log.info("Deleted signup {} for admin archive: status set to DELETED, entries cleared, public messages inactive", signupId);

        } catch (SQLException e) {
            log.error("Failed to delete signup {}", signupId, e);
            throw new RuntimeException("Failed to delete signup", e);
        }
    }

    public boolean removeEntryByUserId(long signupId, long discordUserId) {
        String sql = """
            DELETE FROM younglings.signup_entry
            WHERE signup_id = ?
              AND discord_user_id = ?
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            statement.setLong(2, discordUserId);

            boolean removed = statement.executeUpdate() > 0;

            if (removed) {
                normalizePositions(connection, signupId);
                log.info("Removed Discord user {} from signup {}", discordUserId, signupId);
            }

            return removed;

        } catch (SQLException e) {
            log.error("Failed to remove Discord user {} from signup {}", discordUserId, signupId, e);
            throw new RuntimeException("Failed to remove signup entry", e);
        }
    }

    public List<SignupSession> searchVisibleSignups(long guildId, String query) {
        String sql = """
            SELECT signup_id, guild_id, title, notification_message, max_signups
            FROM younglings.signup
            WHERE guild_id = ?
              AND deleted_at IS NULL
              AND LOWER(title) LIKE LOWER(?)
            ORDER BY created_at ASC
            LIMIT 25
            """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, "%" + query + "%");

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Integer maxSignups = resultSet.getObject("max_signups") == null
                            ? null
                            : resultSet.getInt("max_signups");

                    signups.add(new SignupSession(
                            resultSet.getLong("signup_id"),
                            resultSet.getLong("guild_id"),
                            resultSet.getString("title"),
                            resultSet.getString("notification_message"),
                            maxSignups
                    ));
                }
            }

            return signups;

        } catch (SQLException e) {
            log.error("Failed to search signups for guild {}", guildId, e);
            throw new RuntimeException("Failed to search signups", e);
        }
    }

    public List<SignupSession> getVisibleSignups(long guildId) {
        String sql = """
            SELECT signup_id, guild_id, title, notification_message, max_signups
            FROM younglings.signup
            WHERE guild_id = ?
              AND deleted_at IS NULL
            ORDER BY created_at ASC
            """;

        List<SignupSession> signups = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Integer maxSignups = resultSet.getObject("max_signups") == null
                            ? null
                            : resultSet.getInt("max_signups");

                    signups.add(new SignupSession(
                            resultSet.getLong("signup_id"),
                            resultSet.getLong("guild_id"),
                            resultSet.getString("title"),
                            resultSet.getString("notification_message"),
                            maxSignups
                    ));
                }
            }

            return signups;

        } catch (SQLException e) {
            log.error("Failed to get visible signups for guild {}", guildId, e);
            throw new RuntimeException("Failed to get visible signups", e);
        }
    }

    public void setSignupStatus(long signupId, String status) {
        String sql = """
            UPDATE younglings.signup
            SET status = ?
            WHERE signup_id = ?
              AND deleted_at IS NULL
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status);
            statement.setLong(2, signupId);
            statement.executeUpdate();

            log.info("Set signup {} status to {}", signupId, status);

        } catch (SQLException e) {
            log.error("Failed to set signup {} status to {}", signupId, status, e);
            throw new RuntimeException("Failed to set signup status", e);
        }
    }

    public void softDeleteSignup(long signupId) {
        String sql = """
            UPDATE younglings.signup
            SET deleted_at = NOW(),
                status = 'DELETED'
            WHERE signup_id = ?
              AND deleted_at IS NULL
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            statement.executeUpdate();

            log.info("Soft deleted signup {}", signupId);

        } catch (SQLException e) {
            log.error("Failed to delete signup {}", signupId, e);
            throw new RuntimeException("Failed to delete signup", e);
        }
    }

    public void saveMessage(long signupId, long guildId, long channelId, long messageId, String messageType) {
        String sql = """
                INSERT INTO younglings.signup_message
                    (message_id, signup_id, guild_id, channel_id, message_type)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO UPDATE SET
                    signup_id = EXCLUDED.signup_id,
                    guild_id = EXCLUDED.guild_id,
                    channel_id = EXCLUDED.channel_id,
                    message_type = EXCLUDED.message_type,
                    active = TRUE
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, messageId);
            statement.setLong(2, signupId);
            statement.setLong(3, guildId);
            statement.setLong(4, channelId);
            statement.setString(5, messageType);
            statement.executeUpdate();

            log.info("Saved {} signup message {} for signup {}", messageType, messageId, signupId);

        } catch (SQLException e) {
            log.error("Failed to save signup message {} for signup {}", messageId, signupId, e);
            throw new RuntimeException("Failed to save signup message", e);
        }
    }

    public boolean addEntry(long signupId, long discordUserId, String rsn, long addedByUserId) {
        String sql = """
                INSERT INTO younglings.signup_entry
                    (signup_id, discord_user_id, rsn, queue_position, added_by_user_id)
                VALUES (
                    ?, ?, ?,
                    COALESCE((SELECT MAX(queue_position) + 1 FROM younglings.signup_entry WHERE signup_id = ?), 1),
                    ?
                )
                ON CONFLICT DO NOTHING
                """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            statement.setLong(2, discordUserId);
            statement.setString(3, rsn);
            statement.setLong(4, signupId);
            statement.setLong(5, addedByUserId);

            boolean added = statement.executeUpdate() > 0;

            if (added) {
                log.info("Added RSN '{}' / Discord {} to signup {}", rsn, discordUserId, signupId);
            } else {
                log.info("Skipped duplicate signup entry RSN '{}' / Discord {} for signup {}", rsn, discordUserId, signupId);
            }

            return added;

        } catch (SQLException e) {
            log.error("Failed to add entry to signup {}", signupId, e);
            throw new RuntimeException("Failed to add signup entry", e);
        }
    }
    public SignupEntry getFirstEntry(long signupId) {
        List<SignupEntry> entries = getEntries(signupId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public SignupEntry next(long signupId) {
        String deleteSql = """
            DELETE FROM younglings.signup_entry
            WHERE entry_id = (
                SELECT entry_id
                FROM younglings.signup_entry
                WHERE signup_id = ?
                ORDER BY queue_position ASC
                LIMIT 1
            )
            """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                statement.setLong(1, signupId);
                statement.executeUpdate();
            }

            normalizePositions(connection, signupId);

            connection.commit();
            log.info("Advanced signup {}", signupId);

            return getFirstEntry(signupId);

        } catch (SQLException e) {
            log.error("Failed to advance signup {}", signupId, e);
            throw new RuntimeException("Failed to advance signup", e);
        }
    }

    public SignupEntry removeFirst(long signupId) {
        return next(signupId);
    }

    public SignupEntry skipFirst(long signupId) {
        String selectSql = """
            SELECT entry_id
            FROM younglings.signup_entry
            WHERE signup_id = ?
            ORDER BY queue_position ASC
            LIMIT 2
            """;

        String updateSql = """
            UPDATE younglings.signup_entry
            SET queue_position = ?
            WHERE entry_id = ?
            """;

        try (Connection connection = connectionSupplier.getConnection()) {
            connection.setAutoCommit(false);

            long firstId;
            long secondId;

            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setLong(1, signupId);

                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) return null;
                    firstId = rs.getLong("entry_id");

                    if (!rs.next()) return null;
                    secondId = rs.getLong("entry_id");
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setInt(1, 2);
                statement.setLong(2, firstId);
                statement.executeUpdate();

                statement.setInt(1, 1);
                statement.setLong(2, secondId);
                statement.executeUpdate();
            }

            normalizePositions(connection, signupId);

            connection.commit();
            log.info("Skipped first entry for signup {}", signupId);

            return getFirstEntry(signupId);

        } catch (SQLException e) {
            log.error("Failed to skip first entry for signup {}", signupId, e);
            throw new RuntimeException("Failed to skip signup entry", e);
        }
    }

    public SignupMessage getFirstActivePublicMessage(long signupId) {
        String sql = """
            SELECT message_id, signup_id, guild_id, channel_id, message_type
            FROM younglings.signup_message
            WHERE signup_id = ?
              AND active = TRUE
              AND message_type = 'PUBLIC'
            ORDER BY created_at ASC
            LIMIT 1
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new SignupMessage(
                            resultSet.getLong("message_id"),
                            resultSet.getLong("signup_id"),
                            resultSet.getLong("guild_id"),
                            resultSet.getLong("channel_id"),
                            resultSet.getString("message_type")
                    );
                }
            }

            return null;

        } catch (SQLException e) {
            log.error("Failed to get public message for signup {}", signupId, e);
            throw new RuntimeException("Failed to get public signup message", e);
        }
    }

    public void clearEntries(long signupId) {
        String sql = """
            DELETE FROM younglings.signup_entry
            WHERE signup_id = ?
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);
            int deleted = statement.executeUpdate();

            log.info("Cleared {} entries from signup {}", deleted, signupId);

        } catch (SQLException e) {
            log.error("Failed to clear signup {}", signupId, e);
            throw new RuntimeException("Failed to clear signup", e);
        }
    }

    private void normalizePositions(Connection connection, long signupId) throws SQLException {
        String sql = """
            WITH ordered AS (
                SELECT entry_id,
                       ROW_NUMBER() OVER (ORDER BY queue_position ASC, entry_id ASC) AS new_position
                FROM younglings.signup_entry
                WHERE signup_id = ?
            )
            UPDATE younglings.signup_entry entry
            SET queue_position = ordered.new_position
            FROM ordered
            WHERE entry.entry_id = ordered.entry_id
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, signupId);
            statement.executeUpdate();
        }
    }

    public List<SignupMessage> getActiveMessages(long signupId) {
        String sql = """
            SELECT message_id, signup_id, guild_id, channel_id, message_type
            FROM younglings.signup_message
            WHERE signup_id = ?
              AND active = TRUE
            """;

        List<SignupMessage> messages = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(new SignupMessage(
                            resultSet.getLong("message_id"),
                            resultSet.getLong("signup_id"),
                            resultSet.getLong("guild_id"),
                            resultSet.getLong("channel_id"),
                            resultSet.getString("message_type")
                    ));
                }
            }

            return messages;

        } catch (SQLException e) {
            log.error("Failed to get active messages for signup {}", signupId, e);
            throw new RuntimeException("Failed to get signup messages", e);
        }
    }

    public void markMessageInactive(long messageId) {
        String sql = """
            UPDATE younglings.signup_message
            SET active = FALSE
            WHERE message_id = ?
            """;

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, messageId);
            statement.executeUpdate();

            log.info("Marked signup message {} inactive", messageId);

        } catch (SQLException e) {
            log.error("Failed to mark signup message {} inactive", messageId, e);
            throw new RuntimeException("Failed to mark signup message inactive", e);
        }
    }

    public List<SignupEntry> getEntries(long signupId) {
        String sql = """
                SELECT discord_user_id, rsn
                FROM younglings.signup_entry
                WHERE signup_id = ?
                ORDER BY queue_position ASC
                """;

        List<SignupEntry> entries = new ArrayList<>();

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, signupId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new SignupEntry(
                            resultSet.getLong("discord_user_id"),
                            resultSet.getString("rsn")
                    ));
                }
            }

            return entries;

        } catch (SQLException e) {
            log.error("Failed to get entries for signup {}", signupId, e);
            throw new RuntimeException("Failed to get signup entries", e);
        }
    }
}
