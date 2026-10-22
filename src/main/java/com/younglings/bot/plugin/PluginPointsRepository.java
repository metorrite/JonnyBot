package com.younglings.bot.plugin;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.plugin.PluginPointsRules.Rule;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A clan's points rules for plugin reports ({@code plugin_points_rule}, T-061), and how much a member was already paid today for one type
 * (read from the points ledger by the source keys {@link PluginPointsSink} writes).
 */
@BService
public class PluginPointsRepository {
    private static final Logger log = LoggerFactory.getLogger(PluginPointsRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public PluginPointsRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "plugin points rules", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_points_rule (
                    guild_id BIGINT NOT NULL,
                    type TEXT NOT NULL,
                    points INT NOT NULL,
                    daily_cap INT NOT NULL DEFAULT 0,
                    min_value_gp BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    updated_by BIGINT NULL,
                    PRIMARY KEY (guild_id, type)
                );
                """));
    }

    /** The clan's rules that are on (points above 0). */
    public List<Rule> rules(long guildId) {
        List<Rule> rules = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT type, points, daily_cap, min_value_gp FROM younglings.plugin_points_rule WHERE guild_id = ? AND points > 0")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    PluginEventType type = PluginEventType.parse(rs.getString(1));
                    if (type == null) continue;
                    long min = rs.getLong(4);
                    rules.add(new Rule(type, rs.getInt(2), rs.getInt(3), rs.wasNull() ? null : min));
                }
            }
            return rules;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan's plugin points rules", e);
        }
    }

    /** Replaces the clan's rules with these (one per type; a rule with 0 points is kept as "off" so its cap and value are remembered). */
    public void replace(long guildId, List<Rule> rules, long actorId, Instant at) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_points_rule WHERE guild_id = ?")) {
                    s.setLong(1, guildId);
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO younglings.plugin_points_rule (guild_id, type, points, daily_cap, min_value_gp, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Rule rule : rules) {
                        s.setLong(1, guildId);
                        s.setString(2, rule.type().name());
                        s.setInt(3, rule.points());
                        s.setInt(4, rule.dailyCap());
                        if (rule.minValueGp() == null) s.setNull(5, Types.BIGINT); else s.setLong(5, rule.minValueGp());
                        s.setTimestamp(6, Timestamp.from(at));
                        s.setLong(7, actorId);
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the clan's plugin points rules", e);
        }
    }

    /** Every rule the clan saved, on or off, for the dashboard. */
    public List<Rule> allRules(long guildId) {
        List<Rule> rules = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT type, points, daily_cap, min_value_gp FROM younglings.plugin_points_rule WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    PluginEventType type = PluginEventType.parse(rs.getString(1));
                    if (type == null) continue;
                    long min = rs.getLong(4);
                    rules.add(new Rule(type, rs.getInt(2), rs.getInt(3), rs.wasNull() ? null : min));
                }
            }
            return rules;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan's plugin points rules", e);
        }
    }

    /** Points already paid to the member in this server since {@code since} for one type (source keys start {@code plugin:<TYPE>:}). */
    public long paidSince(long guildId, long userId, PluginEventType type, Instant since) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT COALESCE(SUM(points), 0) FROM younglings.points_ledger
                WHERE guild_id = ? AND user_id = ? AND category = 'PLUGIN' AND source_key LIKE ? AND created_at >= ?""")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setString(3, PluginPointsSink.keyPrefix(type) + "%");
            s.setTimestamp(4, Timestamp.from(since));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read today's plugin points", e);
        }
    }
}
