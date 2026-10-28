package com.runeclans.bot.rolekeep;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.rolekeep.RoleKeepRules.Source;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Storage for kept roles: the server's sticky list, roles remembered for a member (by a person or when they left), and timed roles. */
@BService
public class RoleKeepRepository {
    private static final Logger log = LoggerFactory.getLogger(RoleKeepRepository.class);

    public record Kept(long id, long guildId, long userId, long roleId, Source source, long addedBy, Instant createdAt) {}

    public record Temp(long id, long guildId, long userId, long roleId, Instant expiresAt, long addedBy) {}

    private final ConnectionSupplier connectionSupplier;

    public RoleKeepRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "role keeping", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.sticky_role (
                    guild_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    PRIMARY KEY (guild_id, role_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.role_kept (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    source TEXT NOT NULL,
                    added_by BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    UNIQUE (guild_id, user_id, role_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.role_temp (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL,
                    added_by BIGINT NOT NULL,
                    UNIQUE (guild_id, user_id, role_id)
                );
                """,
                "CREATE INDEX IF NOT EXISTS role_temp_expiry_idx ON younglings.role_temp (expires_at);"
        ));
    }

    // ---------- the sticky list ----------

    public Set<Long> sticky(long guildId) {
        Set<Long> ids = new HashSet<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT role_id FROM younglings.sticky_role WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw fail("read the sticky roles", e);
        }
    }

    public void setSticky(long guildId, Set<Long> roleIds) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.sticky_role WHERE guild_id = ?");
                 PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.sticky_role (guild_id, role_id) VALUES (?, ?)")) {
                delete.setLong(1, guildId);
                delete.executeUpdate();
                for (long id : roleIds) {
                    insert.setLong(1, guildId);
                    insert.setLong(2, id);
                    insert.addBatch();
                }
                insert.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save the sticky roles", e);
        }
    }

    // ---------- remembered roles ----------

    /** Remembers a role for a member. Returns false if it was already remembered (a manual entry is never downgraded to a sticky one). */
    public boolean keep(long guildId, long userId, long roleId, Source source, long addedBy) {
        String sql = "INSERT INTO younglings.role_kept (guild_id, user_id, role_id, source, added_by) VALUES (?, ?, ?, ?, ?) ON CONFLICT (guild_id, user_id, role_id) DO NOTHING";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, roleId);
            s.setString(4, source.name());
            s.setLong(5, addedBy);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("remember the role", e);
        }
    }

    public List<Kept> keptFor(long guildId, long userId) {
        return query("SELECT id, guild_id, user_id, role_id, source, added_by, created_at FROM younglings.role_kept WHERE guild_id = ? AND user_id = ? ORDER BY id", guildId, userId);
    }

    public List<Kept> recentKept(long guildId, int limit) {
        return query("SELECT id, guild_id, user_id, role_id, source, added_by, created_at FROM younglings.role_kept WHERE guild_id = ? AND source = 'MANUAL' ORDER BY id DESC LIMIT " + limit, guildId, null);
    }

    /** Forgets one role for one member. */
    public boolean forget(long guildId, long userId, long roleId) {
        return run("DELETE FROM younglings.role_kept WHERE guild_id = ? AND user_id = ? AND role_id = ?", guildId, userId, roleId) > 0;
    }

    /** Forgets an entry by its id, in this server only. */
    public boolean forgetById(long guildId, long id) {
        return run("DELETE FROM younglings.role_kept WHERE guild_id = ? AND id = ?", guildId, id) > 0;
    }

    /** Forgets what was remembered from the sticky list when the member left (it is remembered again if they leave again). Manual entries stay. */
    public void forgetSticky(long guildId, long userId) {
        run("DELETE FROM younglings.role_kept WHERE guild_id = ? AND user_id = ? AND source = 'STICKY'", guildId, userId);
    }

    public int countManual(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM younglings.role_kept WHERE guild_id = ? AND user_id = ? AND source = 'MANUAL'")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw fail("count remembered roles", e);
        }
    }

    // ---------- timed roles ----------

    /** Puts (or moves) the end time of a timed role. */
    public void setTemp(long guildId, long userId, long roleId, Instant expiresAt, long addedBy) {
        String sql = "INSERT INTO younglings.role_temp (guild_id, user_id, role_id, expires_at, added_by) VALUES (?, ?, ?, ?, ?) ON CONFLICT (guild_id, user_id, role_id) DO UPDATE SET expires_at = EXCLUDED.expires_at, added_by = EXCLUDED.added_by";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, roleId);
            s.setTimestamp(4, Timestamp.from(expiresAt));
            s.setLong(5, addedBy);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the timed role", e);
        }
    }

    public List<Temp> tempFor(long guildId, long userId) {
        return queryTemp("SELECT id, guild_id, user_id, role_id, expires_at, added_by FROM younglings.role_temp WHERE guild_id = ? AND user_id = ? AND expires_at > now()", guildId, userId);
    }

    public List<Temp> tempActive(long guildId, int limit) {
        return queryTemp("SELECT id, guild_id, user_id, role_id, expires_at, added_by FROM younglings.role_temp WHERE guild_id = ? ORDER BY expires_at LIMIT " + limit, guildId, null);
    }

    /** Timed roles whose time is up, across every server. */
    public List<Temp> expired(int limit) {
        return queryTemp("SELECT id, guild_id, user_id, role_id, expires_at, added_by FROM younglings.role_temp WHERE expires_at <= now() ORDER BY expires_at LIMIT " + limit, null, null);
    }

    public boolean deleteTemp(long guildId, long id) {
        return run("DELETE FROM younglings.role_temp WHERE guild_id = ? AND id = ?", guildId, id) > 0;
    }

    public java.util.Optional<Temp> tempById(long guildId, long id) {
        return queryTemp("SELECT id, guild_id, user_id, role_id, expires_at, added_by FROM younglings.role_temp WHERE guild_id = ? AND id = ?", guildId, id).stream().findFirst();
    }

    // ---------- plumbing ----------

    private List<Kept> query(String sql, Long guildId, Long userId) {
        List<Kept> found = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (guildId != null) s.setLong(i++, guildId);
            if (userId != null) s.setLong(i, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) found.add(new Kept(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), Source.valueOf(rs.getString(5)), rs.getLong(6), rs.getTimestamp(7).toInstant()));
            }
            return found;
        } catch (SQLException e) {
            throw fail("read remembered roles", e);
        }
    }

    private List<Temp> queryTemp(String sql, Long first, Long second) {
        List<Temp> found = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (first != null) s.setLong(i++, first);
            if (second != null) s.setLong(i, second);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) found.add(new Temp(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getTimestamp(5).toInstant(), rs.getLong(6)));
            }
            return found;
        } catch (SQLException e) {
            throw fail("read timed roles", e);
        }
    }

    private int run(String sql, long... values) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setLong(i + 1, values[i]);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw fail("update kept roles", e);
        }
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Failed to {}", what, e);
        return new RuntimeException("Failed to " + what, e);
    }
}
