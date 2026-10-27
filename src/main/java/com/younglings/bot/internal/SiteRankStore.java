package com.younglings.bot.internal;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.internal.RankAdvisorRules.Rule;
import com.younglings.bot.internal.RankAdvisorRules.Tick;
import com.younglings.bot.runescape.RuneScapeDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the rank-up advisor (T-594) reads and keeps, by server id (so a bot-less site works the same): the owner's rules per rank, the
 * optional congrats post's settings, and the staff's ticks; and, read only, each roster member's rank, XP since that rank, join date and clan
 * points.
 */
@BService
public class SiteRankStore {
    private static final Logger log = LoggerFactory.getLogger(SiteRankStore.class);

    private final ConnectionSupplier connectionSupplier;

    public SiteRankStore(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer runescape) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "rank-up advisor", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_rule (
                    guild_id BIGINT NOT NULL,
                    rank_name TEXT NOT NULL,
                    min_xp BIGINT NULL,
                    min_days INT NULL,
                    min_caps INT NULL,
                    min_points BIGINT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, rank_name)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_settings (
                    guild_id BIGINT PRIMARY KEY,
                    congrats_on BOOLEAN NOT NULL DEFAULT FALSE,
                    congrats_channel_id BIGINT NULL,
                    congrats_text TEXT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_rank_tick (
                    guild_id BIGINT NOT NULL,
                    rsn_key TEXT NOT NULL,
                    rank_name TEXT NOT NULL,
                    ticked_by BIGINT NOT NULL,
                    ticked_by_name TEXT NULL,
                    ticked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, rsn_key, rank_name)
                );
                """));
    }

    /** The optional congrats post: off until the owner turns it on and picks a channel. */
    public record Congrats(boolean on, Long channelId, String text) {
        public static final Congrats OFF = new Congrats(false, null, null);
    }

    private Connection connection() throws SQLException {
        return connectionSupplier.getConnection();
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Rank-up advisor: could not {}", what, e);
        return new RuntimeException("Could not " + what, e);
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    // ---------- the roster ----------

    /** A roster member's facts before the caps (which come from the Citadel grid). */
    public record Row(String rsn, String rank, Long xpGained, OffsetDateTime rankSince, OffsetDateTime joined, long points) {}

    /** Every active roster member with their XP since their current rank (null before the bot has a starting point) and clan points. */
    public List<Row> roster(long guildId) {
        String sql = """
                SELECT m.rsn, m.clan_rank, m.first_seen, m.clan_joined_at, m.rank_since,
                       CASE WHEN m.rank_xp IS NULL THEN NULL ELSE GREATEST(m.total_xp - m.rank_xp, 0) END AS xp_gained,
                       COALESCE(p.total_points, 0) AS points
                FROM younglings.clan_member m
                LEFT JOIN younglings.clan_member_points p ON p.guild_id = m.guild_id AND LOWER(p.rsn) = LOWER(m.rsn)
                WHERE m.guild_id = ? AND m.active
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            List<Row> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    LocalDate joinedDay = rs.getObject("clan_joined_at", LocalDate.class);
                    OffsetDateTime joined = joinedDay != null ? joinedDay.atStartOfDay().atOffset(ZoneOffset.UTC) : rs.getObject("first_seen", OffsetDateTime.class);
                    out.add(new Row(rs.getString("rsn"), rs.getString("clan_rank"), longOrNull(rs, "xp_gained"), rs.getObject("rank_since", OffsetDateTime.class),
                            joined, rs.getLong("points")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the roster", e);
        }
    }

    // ---------- rules ----------

    /** The site's rules by rank name, in ladder order. */
    public Map<String, Rule> rules(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT rank_name, min_xp, min_days, min_caps, min_points FROM younglings.site_rank_rule WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            Map<String, Rule> found = new LinkedHashMap<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String rank = RankAdvisorRules.canonical(rs.getString("rank_name"));
                    if (rank == null) continue;
                    found.put(rank, new Rule(rank, longOrNull(rs, "min_xp"), intOrNull(rs, "min_days"), intOrNull(rs, "min_caps"), longOrNull(rs, "min_points")));
                }
            }
            Map<String, Rule> out = new LinkedHashMap<>();
            for (String rank : RankAdvisorRules.LADDER) if (found.containsKey(rank)) out.put(rank, found.get(rank));
            return out;
        } catch (SQLException e) {
            throw fail("read the rank rules", e);
        }
    }

    /** Replaces every rule at once (one save of the editor): ranks left out, or with nothing set, have no rule. */
    public void saveRules(long guildId, List<Rule> rules, long by) {
        try (Connection c = connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement clear = c.prepareStatement("DELETE FROM younglings.site_rank_rule WHERE guild_id = ?");
                 PreparedStatement add = c.prepareStatement("""
                         INSERT INTO younglings.site_rank_rule (guild_id, rank_name, min_xp, min_days, min_caps, min_points, updated_by, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                         """)) {
                clear.setLong(1, guildId);
                clear.executeUpdate();
                for (Rule r : rules) {
                    if (r.empty()) continue;
                    add.setLong(1, guildId);
                    add.setString(2, r.rank());
                    setLong(add, 3, r.minXp());
                    setInt(add, 4, r.minDays());
                    setInt(add, 5, r.minCaps());
                    setLong(add, 6, r.minPoints());
                    add.setLong(7, by);
                    add.addBatch();
                }
                add.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw fail("save the rank rules", e);
        }
    }

    private static void setLong(PreparedStatement s, int i, Long v) throws SQLException {
        if (v == null) s.setNull(i, Types.BIGINT);
        else s.setLong(i, v);
    }

    private static void setInt(PreparedStatement s, int i, Integer v) throws SQLException {
        if (v == null) s.setNull(i, Types.INTEGER);
        else s.setInt(i, v);
    }

    // ---------- the congrats post ----------

    public Congrats congrats(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT congrats_on, congrats_channel_id, congrats_text FROM younglings.site_rank_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Congrats(rs.getBoolean("congrats_on"), longOrNull(rs, "congrats_channel_id"), rs.getString("congrats_text")) : Congrats.OFF;
            }
        } catch (SQLException e) {
            throw fail("read the congrats settings", e);
        }
    }

    public void saveCongrats(long guildId, Congrats congrats, long by) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_rank_settings (guild_id, congrats_on, congrats_channel_id, congrats_text, updated_by, updated_at)
                     VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET congrats_on = EXCLUDED.congrats_on, congrats_channel_id = EXCLUDED.congrats_channel_id,
                         congrats_text = EXCLUDED.congrats_text, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setBoolean(2, congrats.on());
            setLong(s, 3, congrats.channelId());
            s.setString(4, congrats.text());
            s.setLong(5, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the congrats settings", e);
        }
    }

    // ---------- ticks ----------

    /** Ticks of the last {@link RankAdvisorRules#TICK_KEEP_DAYS} days, newest first. */
    public List<Tick> ticks(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT rsn_key, rank_name, ticked_by, ticked_by_name, ticked_at FROM younglings.site_rank_tick
                     WHERE guild_id = ? AND ticked_at >= NOW() - (? * INTERVAL '1 day') ORDER BY ticked_at DESC
                     """)) {
            s.setLong(1, guildId);
            s.setInt(2, RankAdvisorRules.TICK_KEEP_DAYS);
            List<Tick> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tick(rs.getString("rsn_key"), rs.getString("rank_name"), rs.getLong("ticked_by"), rs.getString("ticked_by_name"),
                            rs.getObject("ticked_at", OffsetDateTime.class)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the ticks", e);
        }
    }

    /** Ticks a member off for a rank (again: who and when move to now), and drops ticks long past keeping. */
    public void tick(long guildId, String rsnKey, String rank, long by, String byName) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_rank_tick (guild_id, rsn_key, rank_name, ticked_by, ticked_by_name, ticked_at) VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id, rsn_key, rank_name) DO UPDATE SET ticked_by = EXCLUDED.ticked_by, ticked_by_name = EXCLUDED.ticked_by_name,
                         ticked_at = NOW()
                     """);
             PreparedStatement prune = c.prepareStatement(
                     "DELETE FROM younglings.site_rank_tick WHERE guild_id = ? AND ticked_at < NOW() - (? * INTERVAL '1 day')")) {
            s.setLong(1, guildId);
            s.setString(2, rsnKey);
            s.setString(3, rank);
            s.setLong(4, by);
            s.setString(5, byName);
            s.executeUpdate();
            prune.setLong(1, guildId);
            prune.setInt(2, RankAdvisorRules.TICK_KEEP_DAYS);
            prune.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a tick", e);
        }
    }

    public void untick(long guildId, String rsnKey, String rank) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.site_rank_tick WHERE guild_id = ? AND rsn_key = ? AND rank_name = ?")) {
            s.setLong(1, guildId);
            s.setString(2, rsnKey);
            s.setString(3, rank);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("clear a tick", e);
        }
    }

    /** Drops the advisor's rules, settings and ticks with the server's deleted website. */
    public void forget(long guildId) {
        try (Connection c = connection()) {
            for (String table : List.of("site_rank_rule", "site_rank_settings", "site_rank_tick")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, guildId);
                    s.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Rank-up advisor: could not forget server {}", guildId, e);
        }
    }
}
