package com.younglings.bot.autorole;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@BService
public class AutoroleRepository {
    private static final Logger log = LoggerFactory.getLogger(AutoroleRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public AutoroleRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "autorole", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.autorole_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    delay_seconds INTEGER NOT NULL DEFAULT 0,
                    member_role_ids BIGINT[] NOT NULL DEFAULT '{}',
                    bot_role_ids BIGINT[] NOT NULL DEFAULT '{}',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                // members whose roles are waiting for the delay to pass; kept in the database so a restart doesn't lose them
                """
                CREATE TABLE IF NOT EXISTS younglings.autorole_pending (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    due_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (guild_id, user_id)
                );
                """,
                "CREATE INDEX IF NOT EXISTS autorole_pending_due ON younglings.autorole_pending (due_at);"));
    }

    /** This server's autoroles, or the defaults (switched off) if it has never saved any. */
    public AutoroleConfig get(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, delay_seconds, member_role_ids, bot_role_ids FROM younglings.autorole_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return AutoroleConfig.defaults(guildId);
                return new AutoroleConfig(guildId, rs.getBoolean("enabled"), rs.getInt("delay_seconds"), ids(rs.getArray("member_role_ids")), ids(rs.getArray("bot_role_ids")));
            }
        } catch (SQLException e) {
            log.error("Failed to read the autoroles for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the autoroles", e);
        }
    }

    public void save(AutoroleConfig config) {
        String sql = """
                INSERT INTO younglings.autorole_config (guild_id, enabled, delay_seconds, member_role_ids, bot_role_ids, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, delay_seconds = EXCLUDED.delay_seconds,
                    member_role_ids = EXCLUDED.member_role_ids, bot_role_ids = EXCLUDED.bot_role_ids, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setInt(3, config.delaySeconds());
            s.setArray(4, c.createArrayOf("BIGINT", config.memberRoleIds().toArray()));
            s.setArray(5, c.createArrayOf("BIGINT", config.botRoleIds().toArray()));
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the autoroles for guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the autoroles", e);
        }
    }

    public record Pending(long guildId, long userId) {}

    public void addPending(long guildId, long userId, java.time.Instant dueAt) {
        String sql = "INSERT INTO younglings.autorole_pending (guild_id, user_id, due_at) VALUES (?, ?, ?) ON CONFLICT (guild_id, user_id) DO UPDATE SET due_at = EXCLUDED.due_at";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setTimestamp(3, java.sql.Timestamp.from(dueAt));
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to queue the autoroles of {} in guild {}", userId, guildId, e);
        }
    }

    /** Members whose delay has passed. */
    public List<Pending> duePending(java.time.Instant now, int limit) {
        List<Pending> due = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id, user_id FROM younglings.autorole_pending WHERE due_at <= ? ORDER BY due_at LIMIT ?")) {
            s.setTimestamp(1, java.sql.Timestamp.from(now));
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) due.add(new Pending(rs.getLong(1), rs.getLong(2)));
            }
        } catch (SQLException e) {
            log.error("Failed to read the due autoroles", e);
        }
        return due;
    }

    public void removePending(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.autorole_pending WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clear the queued autoroles of {} in guild {}", userId, guildId, e);
        }
    }

    private static List<Long> ids(Array array) throws SQLException {
        List<Long> ids = new ArrayList<>();
        if (array == null) return ids;
        for (Object id : (Object[]) array.getArray()) ids.add(((Number) id).longValue());
        return ids;
    }
}
