package com.runeclans.bot.plugin;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.plugin.PluginPointsRules.Rule;
import com.runeclans.bot.plugin.PluginPointsRules.XpRule;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A clan's points rules for plugin reports ({@code plugin_points_rule}, T-061), and how much a member was already paid today for one type
 * (read from the points ledger by the source keys {@link PluginPointsSink} writes). Also the XP rule ({@code plugin_xp_points_rule}, T-542) and
 * its days ({@code plugin_xp_points_day}): one row per server, member and UTC day they sent RuneSync XP while the rule was on, settled once
 * when {@link PluginXpPoints} has paid (or decided not to pay) that day.
 */
@BService
public class PluginPointsRepository {
    private static final Logger log = LoggerFactory.getLogger(PluginPointsRepository.class);

    /** A server's open XP day: members who sent RuneSync XP that day and haven't been settled yet. */
    public record XpDay(long guildId, LocalDate day) {}

    /** A server whose XP rule is on, and the UTC day it was last switched on (the hub is never asked about days before it, T-712). */
    public record XpRuleOn(long guildId, LocalDate since) {}

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
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_xp_points_rule (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    points INT NOT NULL,
                    per_xp BIGINT NOT NULL,
                    daily_cap INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMPTZ NOT NULL,
                    updated_by BIGINT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_xp_points_day (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    day DATE NOT NULL,
                    xp BIGINT NULL,
                    points INT NULL,
                    settled_at TIMESTAMPTZ NULL,
                    PRIMARY KEY (guild_id, user_id, day)
                );
                """,
                "CREATE INDEX IF NOT EXISTS plugin_xp_points_day_open ON younglings.plugin_xp_points_day (day) WHERE settled_at IS NULL;",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_xp_points_hub_day (
                    guild_id BIGINT NOT NULL,
                    day DATE NOT NULL,
                    asked_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (guild_id, day)
                );
                """,
                "ALTER TABLE younglings.plugin_xp_points_rule ADD COLUMN IF NOT EXISTS on_since DATE NULL;",
                // a rule that was on before on_since existed: on since its last save at the latest
                "UPDATE younglings.plugin_xp_points_rule SET on_since = (updated_at AT TIME ZONE 'UTC')::date WHERE on_since IS NULL AND enabled AND points > 0 AND per_xp > 0;"));
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

    /** The clan's XP rule as saved (on or off), or {@link XpRule#OFF} when it never saved one. */
    public XpRule xpRule(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, points, per_xp, daily_cap FROM younglings.plugin_xp_points_rule WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new XpRule(rs.getBoolean(1), rs.getInt(2), rs.getLong(3), rs.getInt(4)) : XpRule.OFF;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the clan's XP points rule", e);
        }
    }

    /** Saves the clan's XP rule; {@code on_since} becomes the UTC day of {@code at} when the rule goes from off to on, and is kept while it stays on. */
    public void saveXpRule(long guildId, XpRule rule, long actorId, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_xp_points_rule (guild_id, enabled, points, per_xp, daily_cap, updated_at, updated_by, on_since) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, points = EXCLUDED.points, per_xp = EXCLUDED.per_xp, daily_cap = EXCLUDED.daily_cap,
                    updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by,
                    on_since = CASE WHEN EXCLUDED.on_since IS NULL THEN NULL
                        WHEN plugin_xp_points_rule.enabled AND plugin_xp_points_rule.points > 0 AND plugin_xp_points_rule.per_xp > 0
                            AND plugin_xp_points_rule.on_since IS NOT NULL THEN plugin_xp_points_rule.on_since
                        ELSE EXCLUDED.on_since END""")) {
            s.setLong(1, guildId);
            s.setBoolean(2, rule.enabled());
            s.setInt(3, rule.points());
            s.setLong(4, rule.perXp());
            s.setInt(5, rule.dailyCap());
            s.setTimestamp(6, Timestamp.from(at));
            s.setLong(7, actorId);
            if (rule.on()) s.setObject(8, LocalDate.ofInstant(at, ZoneOffset.UTC)); else s.setNull(8, Types.DATE);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the clan's XP points rule", e);
        }
    }

    /** The servers whose XP rule is on, by the numbers they saved, with the day it was switched on (whether the server uses points is checked when a day is settled). */
    public List<XpRuleOn> xpRuleGuilds() {
        List<XpRuleOn> guilds = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT guild_id, COALESCE(on_since, (updated_at AT TIME ZONE 'UTC')::date) FROM younglings.plugin_xp_points_rule
                WHERE enabled AND points > 0 AND per_xp > 0 ORDER BY guild_id""");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) guilds.add(new XpRuleOn(rs.getLong(1), rs.getObject(2, LocalDate.class)));
            return guilds;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the servers with an XP points rule", e);
        }
    }

    /** The member sent RuneSync XP on this UTC day in a server whose XP rule is on: remember to settle it (once; a day already noted stays as it is). */
    public void noteXpDay(long guildId, long userId, LocalDate day) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.plugin_xp_points_day (guild_id, user_id, day) VALUES (?, ?, ?) ON CONFLICT (guild_id, user_id, day) DO NOTHING")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setObject(3, day);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to note a member's XP day", e);
        }
    }

    /** Every server and day with members not yet settled, from {@code from} up to (not including) {@code before}, oldest first. */
    public List<XpDay> openXpDays(LocalDate from, LocalDate before) {
        List<XpDay> days = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT DISTINCT guild_id, day FROM younglings.plugin_xp_points_day WHERE settled_at IS NULL AND day >= ? AND day < ? ORDER BY day, guild_id")) {
            s.setObject(1, from);
            s.setObject(2, before);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) days.add(new XpDay(rs.getLong(1), rs.getObject(2, LocalDate.class)));
            }
            return days;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the open XP days", e);
        }
    }

    /** The members of one server and day not yet settled. */
    public List<Long> openXpMembers(long guildId, LocalDate day) {
        List<Long> users = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT user_id FROM younglings.plugin_xp_points_day WHERE guild_id = ? AND day = ? AND settled_at IS NULL ORDER BY user_id")) {
            s.setLong(1, guildId);
            s.setObject(2, day);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) users.add(rs.getLong(1));
            }
            return users;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a day's XP members", e);
        }
    }

    /** Settles a member's day: what the hub counted ({@code xp} null when it couldn't count them) and the points paid. Only once: false if already settled. */
    public boolean settleXpDay(long guildId, long userId, LocalDate day, Long xp, int points, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.plugin_xp_points_day SET xp = ?, points = ?, settled_at = ? WHERE guild_id = ? AND user_id = ? AND day = ? AND settled_at IS NULL")) {
            if (xp == null) s.setNull(1, Types.BIGINT); else s.setLong(1, xp);
            s.setInt(2, points);
            s.setTimestamp(3, Timestamp.from(at));
            s.setLong(4, guildId);
            s.setLong(5, userId);
            s.setObject(6, day);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to settle a member's XP day", e);
        }
    }

    /** The server and day pairs from {@code from} on whose gainers the hub has already listed (T-712), so each is asked once, restarts included. */
    public Set<XpDay> hubAskedDays(LocalDate from) {
        Set<XpDay> days = new HashSet<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT guild_id, day FROM younglings.plugin_xp_points_hub_day WHERE day >= ?")) {
            s.setObject(1, from);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) days.add(new XpDay(rs.getLong(1), rs.getObject(2, LocalDate.class)));
            }
            return days;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the XP days the hub was asked about", e);
        }
    }

    /** The hub listed this server's gainers for the day and they were all noted: never ask again. */
    public void markHubAsked(long guildId, LocalDate day, Instant now) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.plugin_xp_points_hub_day (guild_id, day, asked_at) VALUES (?, ?, ?) ON CONFLICT (guild_id, day) DO NOTHING")) {
            s.setLong(1, guildId);
            s.setObject(2, day);
            s.setTimestamp(3, Timestamp.from(now));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark an XP day asked of the hub", e);
        }
    }

    /** Forgets XP days before {@code before}, settled or not (the points ledger keeps what was paid). */
    public int forgetXpDays(LocalDate before) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_xp_points_day WHERE day < ?");
             PreparedStatement h = c.prepareStatement("DELETE FROM younglings.plugin_xp_points_hub_day WHERE day < ?")) {
            h.setObject(1, before);
            h.executeUpdate();
            s.setObject(1, before);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to forget old XP days", e);
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
