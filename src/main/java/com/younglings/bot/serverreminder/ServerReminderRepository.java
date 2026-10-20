package com.younglings.bot.serverreminder;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.serverreminder.ServerReminder.Status;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@BService
public class ServerReminderRepository {
    private static final Logger log = LoggerFactory.getLogger(ServerReminderRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public ServerReminderRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "server reminders", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.server_reminder (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    text TEXT NOT NULL,
                    role_id BIGINT NULL,
                    repeat_minutes INTEGER NULL,
                    next_at TIMESTAMPTZ NOT NULL,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    created_by BIGINT NOT NULL,
                    created_by_name TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_sent_at TIMESTAMPTZ NULL,
                    send_count INTEGER NOT NULL DEFAULT 0,
                    error TEXT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS server_reminder_guild ON younglings.server_reminder (guild_id, status, next_at);",
                "CREATE INDEX IF NOT EXISTS server_reminder_due ON younglings.server_reminder (status, next_at);"));
    }

    public long add(long guildId, long channelId, String text, Long roleId, Duration every, Instant nextAt, long createdBy, String createdByName) {
        String sql = "INSERT INTO younglings.server_reminder (guild_id, channel_id, text, role_id, repeat_minutes, next_at, created_by, created_by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            s.setString(3, text);
            if (roleId == null) s.setNull(4, Types.BIGINT); else s.setLong(4, roleId);
            if (every == null) s.setNull(5, Types.INTEGER); else s.setInt(5, (int) every.toMinutes());
            s.setTimestamp(6, Timestamp.from(nextAt));
            s.setLong(7, createdBy);
            s.setString(8, createdByName);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the reminder", e);
        }
    }

    public Optional<ServerReminder> get(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.server_reminder WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the reminder", e);
        }
    }

    /** The server's active reminders by the time they fire, then its most recent finished ones. */
    public List<ServerReminder> forGuild(long guildId, int finishedLimit) {
        String sql = """
                (SELECT * FROM younglings.server_reminder WHERE guild_id = ? AND status = 'ACTIVE' ORDER BY next_at)
                UNION ALL
                (SELECT * FROM younglings.server_reminder WHERE guild_id = ? AND status <> 'ACTIVE' ORDER BY id DESC LIMIT ?)
                """;
        List<ServerReminder> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, guildId);
            s.setInt(3, finishedLimit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the reminders", e);
        }
    }

    public int activeCount(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.server_reminder WHERE guild_id = ? AND status = 'ACTIVE'")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the reminders", e);
        }
    }

    public List<ServerReminder> due(Instant now, int limit) {
        List<ServerReminder> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.server_reminder WHERE status = 'ACTIVE' AND next_at <= ? ORDER BY next_at LIMIT ?")) {
            s.setTimestamp(1, Timestamp.from(now));
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to read the due reminders", e);
        }
        return out;
    }

    /** After a successful post: the next time to post again, or null to finish a one-off. */
    public void sent(long id, Instant sentAt, Instant nextAt) {
        String sql = nextAt == null
                ? "UPDATE younglings.server_reminder SET last_sent_at = ?, send_count = send_count + 1, status = 'DONE' WHERE id = ?"
                : "UPDATE younglings.server_reminder SET last_sent_at = ?, send_count = send_count + 1, next_at = ? WHERE id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setTimestamp(1, Timestamp.from(sentAt));
            if (nextAt == null) {
                s.setLong(2, id);
            } else {
                s.setTimestamp(2, Timestamp.from(nextAt));
                s.setLong(3, id);
            }
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the reminder", e);
        }
    }

    /** Stops a reminder for good: it could not be posted (a reminder that keeps failing is not retried forever). */
    public void fail(long id, String error) {
        exec("UPDATE younglings.server_reminder SET status = 'FAILED', error = ? WHERE id = ?", error, id);
    }

    public boolean cancel(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.server_reminder SET status = 'CANCELLED' WHERE guild_id = ? AND id = ? AND status = 'ACTIVE'")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to cancel the reminder", e);
        }
    }

    private void exec(String sql, String text, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, text);
            s.setLong(2, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Reminder statement failed", e);
        }
    }

    private static ServerReminder map(ResultSet rs) throws SQLException {
        long role = rs.getLong("role_id");
        boolean noRole = rs.wasNull();
        int minutes = rs.getInt("repeat_minutes");
        boolean once = rs.wasNull();
        Timestamp last = rs.getTimestamp("last_sent_at");
        return new ServerReminder(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("channel_id"), rs.getString("text"), noRole ? null : role,
                once ? null : Duration.ofMinutes(minutes), rs.getTimestamp("next_at").toInstant(), Status.valueOf(rs.getString("status")), rs.getLong("created_by"),
                rs.getString("created_by_name"), last == null ? null : last.toInstant(), rs.getInt("send_count"), rs.getString("error"));
    }
}
