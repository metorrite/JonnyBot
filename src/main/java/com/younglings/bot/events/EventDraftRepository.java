package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Event setups someone started but didn't finish: the answers given so far and where they were up to, so an admin can come back to them later (from any
 * device, and any other admin can carry on). A draft is not an event: it becomes one only when it is finished, and is removed then.
 */
@BService
public class EventDraftRepository {
    private static final Logger log = LoggerFactory.getLogger(EventDraftRepository.class);

    /** {@code answers} is the JSON of the setup's answers (see {@link EventOptions}). */
    public record Draft(long id, long guildId, long createdBy, String stream, String step, String template, String title, String answers, Instant createdAt, Instant updatedAt) {}

    private final ConnectionSupplier connectionSupplier;

    public EventDraftRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event drafts", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_draft (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    created_by BIGINT NOT NULL,
                    stream TEXT NOT NULL DEFAULT 'GUIDED',
                    step TEXT NOT NULL DEFAULT 'BASICS',
                    template TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    answers TEXT NOT NULL DEFAULT '{}',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_draft_guild ON younglings.event_draft (guild_id, updated_at DESC);"));
    }

    private static Draft map(ResultSet rs) throws SQLException {
        return new Draft(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("created_by"), rs.getString("stream"), rs.getString("step"), rs.getString("template"), rs.getString("title"),
                rs.getString("answers"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public long create(long guildId, long createdBy, String stream, String step, String template, String title, String answers) {
        String sql = "INSERT INTO younglings.event_draft (guild_id, created_by, stream, step, template, title, answers) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, createdBy);
            s.setString(3, stream);
            s.setString(4, step);
            s.setString(5, template);
            s.setString(6, title);
            s.setString(7, answers);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the draft", e);
        }
    }

    public Optional<Draft> get(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_draft WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the draft", e);
        }
    }

    /** The server's drafts, most recently changed first. */
    public List<Draft> list(long guildId) {
        List<Draft> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_draft WHERE guild_id = ? ORDER BY updated_at DESC, id DESC")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the drafts", e);
        }
    }

    public int count(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.event_draft WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the drafts", e);
        }
    }

    /** Saves the answers and where the setup was up to. False when the draft doesn't exist in this server (it may have been finished or deleted meanwhile). */
    public boolean save(long guildId, long id, String step, String title, String answers) {
        String sql = "UPDATE younglings.event_draft SET step = ?, title = ?, answers = ?, updated_at = NOW() WHERE guild_id = ? AND id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, step);
            s.setString(2, title);
            s.setString(3, answers);
            s.setLong(4, guildId);
            s.setLong(5, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the draft", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_draft WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the draft", e);
        }
    }
}
