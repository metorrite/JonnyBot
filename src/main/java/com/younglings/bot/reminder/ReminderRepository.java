package com.younglings.bot.reminder;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@BService
public class ReminderRepository {
    private static final Logger log = LoggerFactory.getLogger(ReminderRepository.class);

    public static final int MAX_PER_USER = 25;

    /** One reminder: who asked, where it was asked (so a closed DM can fall back to there), what to say and when. */
    public record Reminder(long id, long guildId, long userId, long channelId, String text, Instant dueAt) {}

    private final ConnectionSupplier connectionSupplier;

    public ReminderRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "reminder", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.reminder (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    text TEXT NOT NULL,
                    due_at TIMESTAMPTZ NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS reminder_due ON younglings.reminder (due_at);",
                "CREATE INDEX IF NOT EXISTS reminder_user ON younglings.reminder (user_id);"));
    }

    public int countFor(long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.reminder WHERE user_id = ?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count reminders", e);
        }
    }

    public long add(long guildId, long userId, long channelId, String text, Instant dueAt) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.reminder (guild_id, user_id, channel_id, text, due_at) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, channelId);
            s.setString(4, text);
            s.setTimestamp(5, Timestamp.from(dueAt));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to save a reminder for {}", userId, e);
            throw new RuntimeException("Failed to save the reminder", e);
        }
    }

    public List<Reminder> forUser(long userId) {
        List<Reminder> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.reminder WHERE user_id = ? ORDER BY due_at")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list reminders", e);
        }
    }

    public List<Reminder> due(Instant now, int limit) {
        List<Reminder> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.reminder WHERE due_at <= ? ORDER BY due_at LIMIT ?")) {
            s.setTimestamp(1, Timestamp.from(now));
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to read the due reminders", e);
        }
        return list;
    }

    /** Removes a reminder, but only if it is this person's; false if there was no such reminder of theirs. */
    public boolean delete(long id, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.reminder WHERE id = ? AND user_id = ?")) {
            s.setLong(1, id);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the reminder", e);
        }
    }

    private static Reminder map(ResultSet rs) throws SQLException {
        return new Reminder(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("user_id"), rs.getLong("channel_id"), rs.getString("text"), rs.getTimestamp("due_at").toInstant());
    }
}
