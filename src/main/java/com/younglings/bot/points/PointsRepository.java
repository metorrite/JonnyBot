package com.younglings.bot.points;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.points.PointsRules.Category;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** A server's points: the settings, and the append-only ledger every balance is added up from. */
@BService
public class PointsRepository {
    private static final Logger log = LoggerFactory.getLogger(PointsRepository.class);

    public record Settings(long guildId, boolean enabled, String unit) {
        public static Settings defaults(long guildId) {
            return new Settings(guildId, false, "points");
        }
    }

    public record Entry(long id, long guildId, long userId, long points, String reason, Category category, String sourceKey, Long awardedBy, Instant createdAt) {}

    public record Balance(long userId, long points) {}

    public record Totals(long members, long outstanding, long awarded, long spent) {}

    private final ConnectionSupplier connectionSupplier;

    public PointsRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "points", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.points_settings (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    unit TEXT NOT NULL DEFAULT 'points'
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.points_ledger (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    points BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'MANUAL',
                    source_key TEXT NULL,
                    awarded_by BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS points_ledger_user ON younglings.points_ledger (guild_id, user_id, id DESC);",
                "CREATE INDEX IF NOT EXISTS points_ledger_guild ON younglings.points_ledger (guild_id, id DESC);",
                // The idempotency key: the same source (an approved submission, a bingo tile, an event) can only ever be paid once per server.
                "CREATE UNIQUE INDEX IF NOT EXISTS points_ledger_source ON younglings.points_ledger (guild_id, source_key) WHERE source_key IS NOT NULL;"));
    }

    // ---------- settings ----------

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT enabled, unit FROM younglings.points_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Settings(guildId, rs.getBoolean(1), rs.getString(2)) : Settings.defaults(guildId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the points settings", e);
        }
    }

    public void saveSettings(Settings settings) {
        String sql = "INSERT INTO younglings.points_settings (guild_id, enabled, unit) VALUES (?, ?, ?) ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, unit = EXCLUDED.unit";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, settings.guildId());
            s.setBoolean(2, settings.enabled());
            s.setString(3, settings.unit());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the points settings", e);
        }
    }

    // ---------- the ledger ----------

    /**
     * Adds an entry. Returns its id, or empty when nothing was added: the {@code sourceKey} was already paid, or (for a negative entry) the
     * member's balance couldn't cover it. The balance check and the insert are one statement, so two spends at once can't both succeed.
     */
    public OptionalLong add(long guildId, long userId, long points, String reason, Category category, String sourceKey, Long awardedBy) {
        String sql = """
                INSERT INTO younglings.points_ledger (guild_id, user_id, points, reason, category, source_key, awarded_by)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE ? >= 0 OR (SELECT COALESCE(SUM(points), 0) FROM younglings.points_ledger WHERE guild_id = ? AND user_id = ?) + ? >= 0
                ON CONFLICT DO NOTHING
                RETURNING id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, points);
            s.setString(4, reason);
            s.setString(5, category.name());
            if (sourceKey == null) s.setNull(6, Types.VARCHAR); else s.setString(6, sourceKey);
            if (awardedBy == null) s.setNull(7, Types.BIGINT); else s.setLong(7, awardedBy);
            s.setLong(8, points);
            s.setLong(9, guildId);
            s.setLong(10, userId);
            s.setLong(11, points);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the points", e);
        }
    }

    public long balance(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COALESCE(SUM(points), 0) FROM younglings.points_ledger WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the balance", e);
        }
    }

    /** The highest balances, biggest first (members at zero or below are left out). */
    public List<Balance> top(long guildId, int limit) {
        List<Balance> list = new ArrayList<>();
        String sql = "SELECT user_id, SUM(points) AS total FROM younglings.points_ledger WHERE guild_id = ? GROUP BY user_id HAVING SUM(points) > 0 ORDER BY total DESC, user_id LIMIT ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Balance(rs.getLong(1), rs.getLong(2)));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the leaderboard", e);
        }
    }

    public List<Entry> history(long guildId, long userId, int limit) {
        return entries("SELECT * FROM younglings.points_ledger WHERE guild_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?", guildId, userId, limit);
    }

    public List<Entry> recent(long guildId, int limit) {
        return entries("SELECT * FROM younglings.points_ledger WHERE guild_id = ? ORDER BY id DESC LIMIT ?", guildId, null, limit);
    }

    public Totals totals(long guildId) {
        String sql = """
                SELECT COUNT(DISTINCT user_id), COALESCE(SUM(points), 0), COALESCE(SUM(points) FILTER (WHERE points > 0), 0), COALESCE(-SUM(points) FILTER (WHERE points < 0), 0)
                FROM younglings.points_ledger WHERE guild_id = ?
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to total the points", e);
        }
    }

    private List<Entry> entries(String sql, long guildId, Long userId, int limit) {
        List<Entry> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            s.setLong(i++, guildId);
            if (userId != null) s.setLong(i++, userId);
            s.setInt(i, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long by = rs.getLong("awarded_by");
                    boolean noBy = rs.wasNull();
                    list.add(new Entry(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("user_id"), rs.getLong("points"), rs.getString("reason"),
                            Category.valueOf(rs.getString("category")), rs.getString("source_key"), noBy ? null : by, rs.getTimestamp("created_at").toInstant()));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the points history", e);
        }
    }
}
