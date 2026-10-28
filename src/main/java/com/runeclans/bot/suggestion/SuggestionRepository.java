package com.runeclans.bot.suggestion;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.suggestion.Suggestion.Status;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@BService
public class SuggestionRepository {
    private static final Logger log = LoggerFactory.getLogger(SuggestionRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public SuggestionRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "suggestion", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.suggestion_settings (
                    guild_id BIGINT PRIMARY KEY,
                    channel_id BIGINT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.suggestion (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    author_id BIGINT NOT NULL,
                    content TEXT NOT NULL,
                    channel_id BIGINT NULL,
                    message_id BIGINT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    note TEXT NULL,
                    decided_by BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS suggestion_guild ON younglings.suggestion (guild_id, id DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.suggestion_vote (
                    suggestion_id BIGINT NOT NULL REFERENCES younglings.suggestion (id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    vote INTEGER NOT NULL,
                    PRIMARY KEY (suggestion_id, user_id)
                );
                """));
    }

    // ---- settings ----

    public OptionalLong channel(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT channel_id FROM younglings.suggestion_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return OptionalLong.empty();
                long id = rs.getLong(1);
                return rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(id);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the suggestions channel", e);
        }
    }

    public void setChannel(long guildId, Long channelId) {
        String sql = "INSERT INTO younglings.suggestion_settings (guild_id, channel_id) VALUES (?, ?) ON CONFLICT (guild_id) DO UPDATE SET channel_id = EXCLUDED.channel_id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (channelId == null) s.setNull(2, Types.BIGINT); else s.setLong(2, channelId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the suggestions channel", e);
        }
    }

    // ---- suggestions ----

    public long create(long guildId, long authorId, String content, long channelId) {
        String sql = "INSERT INTO younglings.suggestion (guild_id, author_id, content, channel_id) VALUES (?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, authorId);
            s.setString(3, content);
            s.setLong(4, channelId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the suggestion", e);
        }
    }

    public void setMessage(long id, long messageId) {
        update("UPDATE younglings.suggestion SET message_id = ? WHERE id = ?", messageId, id);
    }

    public Optional<Suggestion> get(long guildId, long id) {
        return one("SELECT * FROM younglings.suggestion WHERE guild_id = ? AND id = ?", guildId, id);
    }

    /** By id alone, for a button press (the message already names the suggestion). */
    public Optional<Suggestion> byId(long id) {
        return one("SELECT * FROM younglings.suggestion WHERE id = ?", id);
    }

    public List<Suggestion> recent(long guildId, int limit) {
        List<Suggestion> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.suggestion WHERE guild_id = ? ORDER BY id DESC LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the suggestions", e);
        }
    }

    public void decide(long id, Status status, String note, long decidedBy) {
        String sql = "UPDATE younglings.suggestion SET status = ?, note = ?, decided_by = ? WHERE id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, status.name());
            if (note == null || note.isBlank()) s.setNull(2, Types.VARCHAR); else s.setString(2, note);
            if (status == Status.PENDING) s.setNull(3, Types.BIGINT); else s.setLong(3, decidedBy);
            s.setLong(4, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the decision", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.suggestion WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the suggestion", e);
        }
    }

    // ---- votes ----

    /** Applies a button press by the {@link SuggestionVote} rule and returns the vote the person now has. */
    public int castVote(long suggestionId, long guildId, long userId, int pressed) {
        try (Connection c = connectionSupplier.getConnection()) {
            int current = SuggestionVote.NONE;
            try (PreparedStatement s = c.prepareStatement("SELECT vote FROM younglings.suggestion_vote WHERE suggestion_id = ? AND user_id = ?")) {
                s.setLong(1, suggestionId);
                s.setLong(2, userId);
                try (ResultSet rs = s.executeQuery()) {
                    if (rs.next()) current = rs.getInt(1);
                }
            }
            int next = SuggestionVote.next(current, pressed);
            if (next == SuggestionVote.NONE) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.suggestion_vote WHERE suggestion_id = ? AND user_id = ?")) {
                    s.setLong(1, suggestionId);
                    s.setLong(2, userId);
                    s.executeUpdate();
                }
            } else {
                String sql = "INSERT INTO younglings.suggestion_vote (suggestion_id, guild_id, user_id, vote) VALUES (?, ?, ?, ?) ON CONFLICT (suggestion_id, user_id) DO UPDATE SET vote = EXCLUDED.vote";
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, suggestionId);
                    s.setLong(2, guildId);
                    s.setLong(3, userId);
                    s.setInt(4, next);
                    s.executeUpdate();
                }
            }
            return next;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the vote", e);
        }
    }

    /** {@code [up, down]} */
    public int[] counts(long suggestionId) {
        String sql = "SELECT COALESCE(SUM(CASE WHEN vote > 0 THEN 1 ELSE 0 END), 0), COALESCE(SUM(CASE WHEN vote < 0 THEN 1 ELSE 0 END), 0) FROM younglings.suggestion_vote WHERE suggestion_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, suggestionId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the votes", e);
        }
    }

    // ---- helpers ----

    private Optional<Suggestion> one(String sql, long... params) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) s.setLong(i + 1, params[i]);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the suggestion", e);
        }
    }

    private void update(String sql, long a, long b) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, a);
            s.setLong(2, b);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Suggestion statement failed", e);
        }
    }

    private static Suggestion map(ResultSet rs) throws SQLException {
        long channel = rs.getLong("channel_id");
        boolean noChannel = rs.wasNull();
        long message = rs.getLong("message_id");
        boolean noMessage = rs.wasNull();
        long decider = rs.getLong("decided_by");
        boolean noDecider = rs.wasNull();
        return new Suggestion(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("author_id"), rs.getString("content"), noChannel ? null : channel,
                noMessage ? null : message, Status.valueOf(rs.getString("status")), rs.getString("note"), noDecider ? null : decider,
                rs.getTimestamp("created_at").toInstant());
    }
}
