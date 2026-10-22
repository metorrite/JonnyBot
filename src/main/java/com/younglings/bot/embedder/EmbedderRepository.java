package com.younglings.bot.embedder;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** The messages composed on the Message embedder page: drafts, and posted messages the bot can still edit, delete and clone. */
@BService
public class EmbedderRepository {
    private static final Logger log = LoggerFactory.getLogger(EmbedderRepository.class);

    /** {@code messageId} is null for a draft. */
    public record Stored(long id, long guildId, String name, String bodyJson, Long channelId, Long messageId, long createdBy,
                         OffsetDateTime updatedAt, OffsetDateTime postedAt) {
        public boolean posted() {
            return messageId != null;
        }
    }

    private static final String COLUMNS = "id, guild_id, name, body_json, channel_id, message_id, created_by, updated_at, posted_at";

    private final ConnectionSupplier connectionSupplier;

    public EmbedderRepository(ConnectionSupplier connectionSupplier, EmbedderDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static Stored map(ResultSet rs) throws SQLException {
        return new Stored(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"), rs.getString("body_json"),
                (Long) rs.getObject("channel_id"), (Long) rs.getObject("message_id"), rs.getLong("created_by"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("posted_at", OffsetDateTime.class));
    }

    public List<Stored> list(long guildId) {
        String sql = "SELECT " + COLUMNS + " FROM younglings.embedder_message WHERE guild_id = ? ORDER BY updated_at DESC, id DESC";
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            List<Stored> results = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) results.add(map(rs));
            }
            return results;
        } catch (SQLException e) {
            log.error("Failed to list embedder messages for guild {}", guildId, e);
            throw new RuntimeException("Failed to list embedder messages", e);
        }
    }

    /** Null if there is no such message in this guild (the guild check keeps one server from touching another's rows). */
    public Stored get(long guildId, long id) {
        String sql = "SELECT " + COLUMNS + " FROM younglings.embedder_message WHERE guild_id = ? AND id = ?";
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to read embedder message {}", id, e);
            throw new RuntimeException("Failed to read embedder message", e);
        }
    }

    public int count(long guildId) {
        String sql = "SELECT COUNT(*) FROM younglings.embedder_message WHERE guild_id = ?";
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count embedder messages for guild {}", guildId, e);
            throw new RuntimeException("Failed to count embedder messages", e);
        }
    }

    /** Adds a row; a message id makes it a posted one. Returns the new id. */
    public long create(long guildId, String name, String bodyJson, Long channelId, Long messageId, long createdBy) {
        String sql = """
                INSERT INTO younglings.embedder_message (guild_id, name, body_json, channel_id, message_id, created_by, posted_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ?::BIGINT IS NULL THEN NULL ELSE NOW() END)
                """;
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, guildId);
            statement.setString(2, name);
            statement.setString(3, bodyJson);
            setNullableLong(statement, 4, channelId);
            setNullableLong(statement, 5, messageId);
            statement.setLong(6, createdBy);
            setNullableLong(statement, 7, messageId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        } catch (SQLException e) {
            log.error("Failed to create embedder message for guild {}", guildId, e);
            throw new RuntimeException("Failed to create embedder message", e);
        }
    }

    public void saveBody(long guildId, long id, String name, String bodyJson) {
        update("UPDATE younglings.embedder_message SET name = ?, body_json = ?, updated_at = NOW() WHERE guild_id = ? AND id = ?",
                s -> {
                    s.setString(1, name);
                    s.setString(2, bodyJson);
                    s.setLong(3, guildId);
                    s.setLong(4, id);
                });
    }

    /** Marks a draft as posted at this channel and message. */
    public void markPosted(long guildId, long id, long channelId, long messageId) {
        update("UPDATE younglings.embedder_message SET channel_id = ?, message_id = ?, posted_at = NOW(), updated_at = NOW() WHERE guild_id = ? AND id = ?",
                s -> {
                    s.setLong(1, channelId);
                    s.setLong(2, messageId);
                    s.setLong(3, guildId);
                    s.setLong(4, id);
                });
    }

    public void delete(long guildId, long id) {
        update("DELETE FROM younglings.embedder_message WHERE guild_id = ? AND id = ?", s -> {
            s.setLong(1, guildId);
            s.setLong(2, id);
        });
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private void update(String sql, Binder binder) {
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Embedder write failed: {}", sql, e);
            throw new RuntimeException("Embedder write failed", e);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }
}
