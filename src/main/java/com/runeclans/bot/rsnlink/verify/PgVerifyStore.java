package com.runeclans.bot.rsnlink.verify;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link VerifyStore} in the link database, beside the link tables (rows go when their link is deleted). The tables are made only when
 * the check is switched on, so with {@code LINK_VERIFY_ENABLED} off the database is untouched. Nothing here holds a Discord id, token or
 * tracking id; the name kept on a watch is the public display name the Hiscores are read under.
 */
public final class PgVerifyStore implements VerifyStore {

    /** Where connections come from (the link service's own pool). */
    public interface Connections {
        Connection get() throws SQLException;
    }

    private static final String SCHEMA_SQL = """
            CREATE TABLE IF NOT EXISTS %1$s.verify_claim (
                id uuid PRIMARY KEY,
                link_id uuid NOT NULL REFERENCES %1$s.link (id) ON DELETE CASCADE,
                skill smallint NOT NULL,
                xp10 bigint NOT NULL,
                claimed_at timestamptz NOT NULL,
                status text NOT NULL,
                baseline_xp bigint NOT NULL DEFAULT -1,
                checked_at timestamptz,
                settled_at timestamptz,
                published_xp bigint
            );
            CREATE INDEX IF NOT EXISTS verify_claim_link ON %1$s.verify_claim (link_id, claimed_at);
            CREATE INDEX IF NOT EXISTS verify_claim_open ON %1$s.verify_claim (link_id) WHERE status IN ('UNCHECKED', 'PENDING');
            CREATE TABLE IF NOT EXISTS %1$s.verify_watch (
                link_id uuid PRIMARY KEY REFERENCES %1$s.link (id) ON DELETE CASCADE,
                rsn text NOT NULL,
                last_state text NOT NULL,
                last_report_at timestamptz NOT NULL,
                next_check_at timestamptz,
                attempt integer NOT NULL DEFAULT 0,
                budget_day date,
                budget_used integer NOT NULL DEFAULT 0,
                ironman boolean,
                ironman_checked_at timestamptz,
                matches integer NOT NULL DEFAULT 0,
                misses integer NOT NULL DEFAULT 0,
                last_published_at timestamptz,
                corroborated_at timestamptz,
                created_at timestamptz NOT NULL
            );
            CREATE INDEX IF NOT EXISTS verify_watch_due ON %1$s.verify_watch (next_check_at) WHERE next_check_at IS NOT NULL AND corroborated_at IS NULL;
            """;

    private final Connections connections;
    private final String schema;

    public PgVerifyStore(Connections connections, String schema) {
        if (!schema.matches("[a-z_][a-z0-9_]{0,40}")) throw new IllegalArgumentException("Bad schema name");
        this.connections = connections;
        this.schema = schema;
    }

    /** Makes the tables. The link service's own schema (with {@code link}) must exist first. */
    public void migrate() {
        try (Connection c = connections.get(); Statement s = c.createStatement()) {
            s.execute(SCHEMA_SQL.formatted(schema));
        } catch (SQLException e) {
            throw new IllegalStateException("The link verification tables couldn't be prepared: " + e.getSQLState(), e);
        }
    }

    @Override
    public void insertClaim(Claim c) {
        update("INSERT INTO {s}.verify_claim (id, link_id, skill, xp10, claimed_at, status, baseline_xp, checked_at, settled_at, published_xp) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", c.id(), c.linkId(), c.skill(), c.xp10(), ts(c.claimedAt()), c.status().name(), c.baselineXp(),
                ts(c.checkedAt()), ts(c.settledAt()), c.publishedXp());
    }

    @Override
    public void updateClaim(Claim c) {
        update("UPDATE {s}.verify_claim SET status = ?, baseline_xp = ?, checked_at = ?, settled_at = ?, published_xp = ? WHERE id = ?",
                c.status().name(), c.baselineXp(), ts(c.checkedAt()), ts(c.settledAt()), c.publishedXp(), c.id());
    }

    @Override
    public List<Claim> openClaims(UUID linkId) {
        return claims("SELECT * FROM {s}.verify_claim WHERE link_id = ? AND status IN ('UNCHECKED', 'PENDING') ORDER BY claimed_at, id", linkId);
    }

    @Override
    public List<Claim> matchedClaims(UUID linkId) {
        return claims("SELECT * FROM {s}.verify_claim WHERE link_id = ? AND status = 'MATCH' ORDER BY claimed_at, id", linkId);
    }

    @Override
    public Map<ClaimStatus, Integer> claimCounts(UUID linkId) {
        Map<ClaimStatus, Integer> out = new EnumMap<>(ClaimStatus.class);
        query("SELECT status, count(*) FROM {s}.verify_claim WHERE link_id = ? GROUP BY status", rs -> out.put(ClaimStatus.valueOf(rs.getString(1)), rs.getInt(2)), linkId);
        return out;
    }

    @Override
    public int claimsSince(UUID linkId, Instant since) {
        int[] n = {0};
        query("SELECT count(*) FROM {s}.verify_claim WHERE link_id = ? AND claimed_at >= ?", rs -> n[0] = rs.getInt(1), linkId, ts(since));
        return n[0];
    }

    @Override
    public Optional<Watch> watch(UUID linkId) {
        List<Watch> found = watches("SELECT * FROM {s}.verify_watch WHERE link_id = ?", linkId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    @Override
    public void saveWatch(Watch w) {
        update("""
                INSERT INTO {s}.verify_watch (link_id, rsn, last_state, last_report_at, next_check_at, attempt, budget_day, budget_used, ironman,
                    ironman_checked_at, matches, misses, last_published_at, corroborated_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (link_id) DO UPDATE SET rsn = excluded.rsn, last_state = excluded.last_state, last_report_at = excluded.last_report_at,
                    next_check_at = excluded.next_check_at, attempt = excluded.attempt, budget_day = excluded.budget_day, budget_used = excluded.budget_used,
                    ironman = excluded.ironman, ironman_checked_at = excluded.ironman_checked_at, matches = excluded.matches, misses = excluded.misses,
                    last_published_at = excluded.last_published_at, corroborated_at = excluded.corroborated_at""",
                w.linkId(), w.rsn(), w.lastState(), ts(w.lastReportAt()), ts(w.nextCheckAt()), w.attempt(),
                w.budgetDay() == null ? null : Date.valueOf(w.budgetDay()), w.budgetUsed(), w.ironman(), ts(w.ironmanCheckedAt()), w.matches(), w.misses(),
                ts(w.lastPublishedAt()), ts(w.corroboratedAt()), ts(w.createdAt()));
    }

    @Override
    public List<Watch> dueWatches(Instant now, int limit) {
        return watches("SELECT * FROM {s}.verify_watch WHERE corroborated_at IS NULL AND next_check_at IS NOT NULL AND next_check_at <= ? "
                + "ORDER BY next_check_at LIMIT ?", ts(now), limit);
    }

    @Override
    public Map<String, Integer> totals(Instant now) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ClaimStatus s : ClaimStatus.values()) out.put("claims_" + s.name().toLowerCase(), 0);
        query("SELECT status, count(*) FROM {s}.verify_claim GROUP BY status", rs -> out.put("claims_" + rs.getString(1).toLowerCase(), rs.getInt(2)));
        query("SELECT count(*), count(*) FILTER (WHERE corroborated_at IS NULL AND next_check_at <= ?), count(corroborated_at) FROM {s}.verify_watch", rs -> {
            out.put("watches", rs.getInt(1));
            out.put("watchesDue", rs.getInt(2));
            out.put("corroborated", rs.getInt(3));
        }, ts(now));
        return out;
    }

    @Override
    public List<Watch> recentlyCorroborated(int limit) {
        return watches("SELECT * FROM {s}.verify_watch WHERE corroborated_at IS NOT NULL ORDER BY corroborated_at DESC LIMIT ?", limit);
    }

    @Override
    public int sweep(Instant cutoff) {
        return update("DELETE FROM {s}.verify_claim WHERE settled_at IS NOT NULL AND settled_at < ?", ts(cutoff))
                + update("DELETE FROM {s}.verify_watch WHERE corroborated_at IS NULL AND next_check_at IS NULL AND last_report_at < ?", ts(cutoff));
    }

    // ---------- plumbing ----------

    private interface Row {
        void read(ResultSet rs) throws SQLException;
    }

    private String sql(String template) {
        return template.replace("{s}", schema);
    }

    private int update(String template, Object... args) {
        try (Connection c = connections.get(); PreparedStatement s = c.prepareStatement(sql(template))) {
            bind(s, args);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Link verification store: " + e.getSQLState(), e);
        }
    }

    private void query(String template, Row row, Object... args) {
        try (Connection c = connections.get(); PreparedStatement s = c.prepareStatement(sql(template))) {
            bind(s, args);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) row.read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Link verification store: " + e.getSQLState(), e);
        }
    }

    private List<Claim> claims(String template, Object... args) {
        List<Claim> out = new ArrayList<>();
        query(template, rs -> out.add(new Claim(rs.getObject("id", UUID.class), rs.getObject("link_id", UUID.class), rs.getInt("skill"),
                rs.getLong("xp10"), instant(rs, "claimed_at"), ClaimStatus.valueOf(rs.getString("status")), rs.getLong("baseline_xp"),
                instant(rs, "checked_at"), instant(rs, "settled_at"), rs.getObject("published_xp") == null ? null : rs.getLong("published_xp"))), args);
        return out;
    }

    private List<Watch> watches(String template, Object... args) {
        List<Watch> out = new ArrayList<>();
        query(template, rs -> {
            Date day = rs.getDate("budget_day");
            Object iron = rs.getObject("ironman");
            out.add(new Watch(rs.getObject("link_id", UUID.class), rs.getString("rsn"), rs.getString("last_state"), instant(rs, "last_report_at"),
                    instant(rs, "next_check_at"), rs.getInt("attempt"), day == null ? null : day.toLocalDate(), rs.getInt("budget_used"),
                    iron == null ? null : (Boolean) iron, instant(rs, "ironman_checked_at"), rs.getInt("matches"), rs.getInt("misses"),
                    instant(rs, "last_published_at"), instant(rs, "corroborated_at"), instant(rs, "created_at")));
        }, args);
        return out;
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) s.setNull(i + 1, Types.NULL);
            else s.setObject(i + 1, a);
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
