package com.younglings.bot.warning;

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

/** A record of warnings given to members, kept per server. */
@BService
public class WarningRepository {
    private static final Logger log = LoggerFactory.getLogger(WarningRepository.class);

    public record Warning(long id, long guildId, long userId, long moderatorId, String reason, Instant createdAt) {}

    private final ConnectionSupplier connectionSupplier;

    public WarningRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "warning", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.member_warning (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    moderator_id BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS member_warning_user ON younglings.member_warning (guild_id, user_id, id DESC);",
                "CREATE INDEX IF NOT EXISTS member_warning_guild ON younglings.member_warning (guild_id, id DESC);"));
    }

    public long add(long guildId, long userId, long moderatorId, String reason) {
        String sql = "INSERT INTO younglings.member_warning (guild_id, user_id, moderator_id, reason) VALUES (?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, moderatorId);
            s.setString(4, reason);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the warning", e);
        }
    }

    public List<Warning> forUser(long guildId, long userId) {
        return list("SELECT * FROM younglings.member_warning WHERE guild_id = ? AND user_id = ? ORDER BY id DESC LIMIT 100", guildId, userId);
    }

    public List<Warning> recent(long guildId, int limit) {
        return list("SELECT * FROM younglings.member_warning WHERE guild_id = ? ORDER BY id DESC LIMIT ?", guildId, limit);
    }

    public int countFor(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.member_warning WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the warnings", e);
        }
    }

    /** Removes one warning from this server. Returns false if there was no such warning here. */
    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.member_warning WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove the warning", e);
        }
    }

    private List<Warning> list(String sql, Object... params) {
        List<Warning> warnings = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    warnings.add(new Warning(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("user_id"), rs.getLong("moderator_id"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant()));
                }
            }
            return warnings;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the warnings", e);
        }
    }
}
