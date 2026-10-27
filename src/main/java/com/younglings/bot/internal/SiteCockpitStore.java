package com.younglings.bot.internal;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.internal.CockpitRules.Mark;
import com.younglings.bot.internal.CockpitRules.Member;
import com.younglings.bot.internal.CockpitRules.Rename;
import com.younglings.bot.internal.CockpitRules.RosterEvent;
import com.younglings.bot.runescape.RuneScapeDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the site admin's Clan health page (T-593) reads and keeps, by server id (so a bot-less site works the same): the roster with when each
 * member's clan Hiscores XP total last changed, the roster's joins and leaves, suspected renames and who is linked in the server; and the two
 * things the page stores itself, the site's quiet threshold and the staff's "done" marks.
 */
@BService
public class SiteCockpitStore {
    private static final Logger log = LoggerFactory.getLogger(SiteCockpitStore.class);
    /** Marks older than this are deleted when someone marks a row (they stopped counting after {@link CockpitRules#MARK_DAYS}). */
    static final int MARK_KEEP_DAYS = 60;

    private final ConnectionSupplier connectionSupplier;

    public SiteCockpitStore(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer runescape) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "clan health", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_cockpit_settings (
                    guild_id BIGINT PRIMARY KEY,
                    quiet_days SMALLINT NOT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_cockpit_mark (
                    guild_id BIGINT NOT NULL,
                    kind TEXT NOT NULL,
                    rsn_key TEXT NOT NULL,
                    marked_by BIGINT NOT NULL,
                    marked_by_name TEXT NULL,
                    marked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, kind, rsn_key)
                );
                """));
    }

    private Connection connection() throws SQLException {
        return connectionSupplier.getConnection();
    }

    private static OffsetDateTime at(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Clan health: could not {}", what, e);
        return new RuntimeException("Could not " + what, e);
    }

    // ---------- the roster ----------

    /** Every active roster member, with their Discord id when the name is linked in the server. */
    public List<Member> members(long guildId, Map<String, Long> links) {
        String sql = """
                SELECT rsn, clan_rank, first_seen, last_seen, xp_changed_at, xp_watched_since
                FROM younglings.clan_member WHERE guild_id = ? AND active
                """;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            List<Member> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String rsn = rs.getString("rsn");
                    out.add(new Member(rsn, rs.getString("clan_rank"), at(rs, "first_seen"), at(rs, "last_seen"), at(rs, "xp_changed_at"),
                            at(rs, "xp_watched_since"), links.get(CockpitRules.keyOf(rsn))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the roster", e);
        }
    }

    /** Discord ids by name key for every RuneScape name linked in the server (a leaver's link stays, so leavers are found here too). */
    public Map<String, Long> links(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT LOWER(rsn) AS k, discord_user_id FROM younglings.player_link WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            Map<String, Long> out = new HashMap<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.putIfAbsent(rs.getString("k"), rs.getLong("discord_user_id"));
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the links", e);
        }
    }

    /** Joins and leaves the roster sync recorded since {@code from}, newest first. */
    public List<RosterEvent> rosterEvents(long guildId, OffsetDateTime from) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT rsn, event_type, event_at FROM younglings.clan_roster_event
                     WHERE guild_id = ? AND event_at >= ? ORDER BY event_at DESC LIMIT 2000
                     """)) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(from.toInstant()));
            List<RosterEvent> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new RosterEvent(rs.getString("rsn"), rs.getString("event_type"), at(rs, "event_at")));
            }
            return out;
        } catch (SQLException e) {
            throw fail("read joins and leaves", e);
        }
    }

    /** Renames the roster sync detected since {@code from} that staff haven't rejected. */
    public List<Rename> renames(long guildId, OffsetDateTime from) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT old_rsn, new_rsn, status FROM younglings.rsn_rename_candidate
                     WHERE guild_id = ? AND detected_at >= ? AND status <> 'REJECTED' ORDER BY detected_at DESC
                     """)) {
            s.setLong(1, guildId);
            s.setTimestamp(2, Timestamp.from(from.toInstant()));
            List<Rename> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Rename(rs.getString("old_rsn"), rs.getString("new_rsn"), rs.getString("status")));
            }
            return out;
        } catch (SQLException e) {
            throw fail("read renames", e);
        }
    }

    /** Whether the name was ever on this server's roster (a mark may only name one). */
    public boolean knownName(long guildId, String rsn) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.clan_member WHERE guild_id = ? AND LOWER(rsn) = LOWER(?)")) {
            s.setLong(1, guildId);
            s.setString(2, rsn.strip());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw fail("check a name", e);
        }
    }

    // ---------- settings ----------

    /** The site's quiet threshold, or null when it never saved one (the page then uses {@link CockpitRules#DEFAULT_QUIET_DAYS}). */
    public Integer quietDays(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("SELECT quiet_days FROM younglings.site_cockpit_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? (int) rs.getShort(1) : null;
            }
        } catch (SQLException e) {
            throw fail("read the settings", e);
        }
    }

    public void saveQuietDays(long guildId, int days, long by) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_cockpit_settings (guild_id, quiet_days, updated_by, updated_at) VALUES (?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET quiet_days = EXCLUDED.quiet_days, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setShort(2, (short) days);
            s.setLong(3, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the settings", e);
        }
    }

    // ---------- done marks ----------

    public List<Mark> marks(long guildId) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT kind, rsn_key, marked_by, marked_by_name, marked_at FROM younglings.site_cockpit_mark
                     WHERE guild_id = ? AND marked_at >= NOW() - (? * INTERVAL '1 day')
                     """)) {
            s.setLong(1, guildId);
            s.setInt(2, CockpitRules.MARK_DAYS);
            List<Mark> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Mark(rs.getString("kind"), rs.getString("rsn_key"), rs.getLong("marked_by"), rs.getString("marked_by_name"), at(rs, "marked_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail("read the done marks", e);
        }
    }

    /** Marks a row done (again: the time and who move to now), and drops marks long past counting. */
    public void mark(long guildId, String kind, String rsnKey, long by, String byName) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_cockpit_mark (guild_id, kind, rsn_key, marked_by, marked_by_name, marked_at) VALUES (?, ?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id, kind, rsn_key) DO UPDATE SET marked_by = EXCLUDED.marked_by, marked_by_name = EXCLUDED.marked_by_name,
                         marked_at = NOW()
                     """);
             PreparedStatement prune = c.prepareStatement(
                     "DELETE FROM younglings.site_cockpit_mark WHERE guild_id = ? AND marked_at < NOW() - (? * INTERVAL '1 day')")) {
            s.setLong(1, guildId);
            s.setString(2, kind);
            s.setString(3, rsnKey);
            s.setLong(4, by);
            s.setString(5, byName);
            s.executeUpdate();
            prune.setLong(1, guildId);
            prune.setInt(2, MARK_KEEP_DAYS);
            prune.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a done mark", e);
        }
    }

    public void unmark(long guildId, String kind, String rsnKey) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.site_cockpit_mark WHERE guild_id = ? AND kind = ? AND rsn_key = ?")) {
            s.setLong(1, guildId);
            s.setString(2, kind);
            s.setString(3, rsnKey);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("clear a done mark", e);
        }
    }

    /** Drops the page's settings and marks with the server's deleted website. */
    public void forget(long guildId) {
        try (Connection c = connection();
             PreparedStatement settings = c.prepareStatement("DELETE FROM younglings.site_cockpit_settings WHERE guild_id = ?");
             PreparedStatement marks = c.prepareStatement("DELETE FROM younglings.site_cockpit_mark WHERE guild_id = ?")) {
            settings.setLong(1, guildId);
            settings.executeUpdate();
            marks.setLong(1, guildId);
            marks.executeUpdate();
        } catch (SQLException e) {
            log.warn("Clan health: could not forget server {}", guildId, e);
        }
    }
}
