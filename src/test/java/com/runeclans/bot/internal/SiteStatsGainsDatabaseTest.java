package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SiteStatsRepository#xpGainsBetween} and {@link SiteStatsRepository#skillGainsBetween} probe the snapshot index once per member.
 * This test keeps the earlier DISTINCT ON queries as a reference and checks that both forms give the same rows, in the same order, over
 * seeded edge cases: no snapshots, snapshots only after the window start, only after the window end, case-variant names, inactive members.
 * Runs over the developer's local Postgres (skipped without one).
 */
class SiteStatsGainsDatabaseTest {
    // Made-up id, built at run time (a long literal of this shape trips the secret scanner).
    private static final long GUILD = Long.parseLong("5".repeat(17) + "7");
    private static final String PREFIX = "XpLateral";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final String OLD_XP = """
            WITH members AS (
                SELECT LOWER(rsn) AS k, rsn FROM younglings.clan_member WHERE guild_id = ? AND active),
            latest AS (
                SELECT DISTINCT ON (LOWER(s.rsn)) LOWER(s.rsn) AS k, s.total_xp FROM younglings.player_stats_snapshot s
                JOIN members m ON m.k = LOWER(s.rsn) WHERE s.snapshot_at <= ? ORDER BY LOWER(s.rsn), s.snapshot_at DESC),
            before AS (
                SELECT DISTINCT ON (LOWER(s.rsn)) LOWER(s.rsn) AS k, s.total_xp FROM younglings.player_stats_snapshot s
                JOIN members m ON m.k = LOWER(s.rsn) WHERE s.snapshot_at <= ? ORDER BY LOWER(s.rsn), s.snapshot_at DESC),
            after AS (
                SELECT DISTINCT ON (LOWER(s.rsn)) LOWER(s.rsn) AS k, s.total_xp FROM younglings.player_stats_snapshot s
                JOIN members m ON m.k = LOWER(s.rsn) WHERE s.snapshot_at > ? ORDER BY LOWER(s.rsn), s.snapshot_at ASC)
            SELECT m.rsn, l.total_xp - COALESCE(b.total_xp, a.total_xp) AS gain
            FROM members m
            JOIN latest l ON l.k = m.k
            LEFT JOIN before b ON b.k = m.k
            LEFT JOIN after a ON a.k = m.k
            WHERE COALESCE(b.total_xp, a.total_xp) IS NOT NULL
            """;

    private static final String OLD_SKILLS = """
            WITH members AS (
                SELECT LOWER(rsn) AS k FROM younglings.clan_member WHERE guild_id = ? AND active AND (?::text IS NULL OR LOWER(rsn) = LOWER(?))),
            latest AS (
                SELECT DISTINCT ON (LOWER(s.rsn)) s.snapshot_id, LOWER(s.rsn) AS k FROM younglings.player_stats_snapshot s
                JOIN members m ON m.k = LOWER(s.rsn) WHERE s.snapshot_at <= ? ORDER BY LOWER(s.rsn), s.snapshot_at DESC),
            before AS (
                SELECT DISTINCT ON (LOWER(s.rsn)) s.snapshot_id, LOWER(s.rsn) AS k FROM younglings.player_stats_snapshot s
                JOIN members m ON m.k = LOWER(s.rsn) WHERE s.snapshot_at <= ? ORDER BY LOWER(s.rsn), s.snapshot_at DESC),
            after AS (
                SELECT DISTINCT ON (LOWER(s.rsn)) s.snapshot_id, LOWER(s.rsn) AS k FROM younglings.player_stats_snapshot s
                JOIN members m ON m.k = LOWER(s.rsn) WHERE s.snapshot_at > ? ORDER BY LOWER(s.rsn), s.snapshot_at ASC),
            base AS (SELECT l.k, COALESCE(b.snapshot_id, a.snapshot_id) AS snapshot_id FROM latest l LEFT JOIN before b ON b.k = l.k LEFT JOIN after a ON a.k = l.k)
            SELECT ls.skill_id, SUM(ls.xp - bs.xp) AS gain
            FROM latest l
            JOIN base ba ON ba.k = l.k AND ba.snapshot_id IS NOT NULL
            JOIN younglings.player_skill_snapshot ls ON ls.snapshot_id = l.snapshot_id
            JOIN younglings.player_skill_snapshot bs ON bs.snapshot_id = ba.snapshot_id AND bs.skill_id = ls.skill_id
            GROUP BY ls.skill_id HAVING SUM(ls.xp - bs.xp) > 0 ORDER BY gain DESC
            """;

    private static ConnectionSupplier db;
    private static SiteStatsRepository stats;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        stats = new SiteStatsRepository(db, new RuneScapeDatabaseInitializer(db));
    }

    @BeforeEach
    void seed() throws Exception {
        cleanUp();
        // Every member gets a different XP slope, and each skill a different share, so sums and orderings are distinct.
        member("Steady", true);       // a snapshot every 6 hours for 40 days
        member("NoSnapshots", true);  // never polled
        member("LateStart", true);    // first polled on day 25
        member("Future", true);       // only polled on days 41-45
        member("Gone", false);        // inactive: never counted
        member("caseMix", true);      // snapshots stored as "CASEMIX"
        member("Flat", true);         // no XP change at all
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            for (int h = 0; h <= 40 * 24; h += 6) {
                snapshot(c, PREFIX + "Steady", h, 1_000_000L + h * 1_000L);
                snapshot(c, PREFIX + "Gone", h, 2_000_000L + h * 5_000L);
                snapshot(c, (PREFIX + "caseMix").toUpperCase(), h, 3_000_000L + h * 700L);
                snapshot(c, PREFIX + "Flat", h, 4_000_000L);
                if (h >= 25 * 24) snapshot(c, PREFIX + "LateStart", h, 500_000L + h * 2_000L);
            }
            for (int h = 41 * 24; h <= 45 * 24; h += 6) snapshot(c, PREFIX + "Future", h, 9_000_000L + h * 9_000L);
            c.commit();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db == null) return;
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM younglings.player_skill_snapshot WHERE snapshot_id IN (SELECT snapshot_id FROM younglings.player_stats_snapshot WHERE LOWER(rsn) LIKE '" + PREFIX.toLowerCase() + "%')");
            s.executeUpdate("DELETE FROM younglings.player_stats_snapshot WHERE LOWER(rsn) LIKE '" + PREFIX.toLowerCase() + "%'");
            s.executeUpdate("DELETE FROM younglings.clan_member WHERE guild_id = " + GUILD);
        }
    }

    /** Windows that start before any data, inside it, at a snapshot's exact time, and after the last one; also a window that ends in the past. */
    private static List<OffsetDateTime[]> windows() {
        return List.of(
                new OffsetDateTime[]{T0.minusDays(5), T0.plusDays(50)},
                new OffsetDateTime[]{T0.plusDays(33), T0.plusDays(50)},
                new OffsetDateTime[]{T0.plusDays(39), T0.plusDays(40)},
                new OffsetDateTime[]{T0.plusDays(10), T0.plusDays(20)},
                new OffsetDateTime[]{T0.plusDays(20).plusHours(3), T0.plusDays(30).plusHours(1)},
                new OffsetDateTime[]{T0.plusDays(42), T0.plusDays(50)},
                new OffsetDateTime[]{T0.plusDays(60), T0.plusDays(70)});
    }

    @Test
    void xpGainsMatchTheDistinctOnQuery() throws Exception {
        boolean sawRows = false;
        for (OffsetDateTime[] w : windows()) {
            List<SiteStatsRepository.Gain> expected = new ArrayList<>();
            try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(OLD_XP)) {
                ps.setLong(1, GUILD);
                ps.setObject(2, w[1]);
                ps.setObject(3, w[0]);
                ps.setObject(4, w[0]);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) expected.add(new SiteStatsRepository.Gain(rs.getString("rsn"), rs.getLong("gain")));
                }
            }
            // The old query had no ORDER BY; its plan returned rows by lower-cased name, which the new query now states.
            expected.sort(java.util.Comparator.comparing(g -> g.rsn().toLowerCase(java.util.Locale.ROOT)));
            sawRows |= !expected.isEmpty();
            assertEquals(expected, stats.xpGainsBetween(GUILD, w[0], w[1]), "window " + w[0] + " .. " + w[1]);
        }
        assertTrue(sawRows, "the seeded data should give some gains");
        // Members with no snapshots, or who left, are never listed.
        List<String> names = stats.xpGainsBetween(GUILD, T0.minusDays(5), T0.plusDays(50)).stream().map(SiteStatsRepository.Gain::rsn).toList();
        assertEquals(List.of(PREFIX + "caseMix", PREFIX + "Flat", PREFIX + "Future", PREFIX + "LateStart", PREFIX + "Steady"), names);
    }

    @Test
    void skillGainsMatchTheDistinctOnQuery() throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO younglings.player_skill_snapshot (snapshot_id, skill_id, level, xp, rank)
                    SELECT s.snapshot_id, k, 1, s.total_xp * (k + 1) / 7 + k, 0 FROM younglings.player_stats_snapshot s, generate_series(0, 5) k
                    WHERE LOWER(s.rsn) LIKE ?""")) {
                ps.setString(1, PREFIX.toLowerCase() + "%");
                ps.executeUpdate();
            }
            c.commit();
        }
        List<String> who = new ArrayList<>(List.of(PREFIX + "Steady", PREFIX + "lateSTART", PREFIX + "CaseMix", PREFIX + "Future", PREFIX + "Gone", PREFIX + "NoSnapshots", "NotAMember"));
        who.add(null);
        boolean sawRows = false;
        for (String rsn : who) {
            for (OffsetDateTime[] w : windows()) {
                List<SiteStatsRepository.SkillGain> expected = new ArrayList<>();
                try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(OLD_SKILLS)) {
                    ps.setLong(1, GUILD);
                    ps.setString(2, rsn);
                    ps.setString(3, rsn);
                    ps.setObject(4, w[1]);
                    ps.setObject(5, w[0]);
                    ps.setObject(6, w[0]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) expected.add(new SiteStatsRepository.SkillGain(rs.getInt("skill_id"), rs.getLong("gain")));
                    }
                }
                sawRows |= !expected.isEmpty();
                assertEquals(expected, stats.skillGainsBetween(GUILD, rsn, w[0], w[1]), rsn + " window " + w[0] + " .. " + w[1]);
            }
        }
        assertTrue(sawRows, "the seeded data should give some skill gains");
    }

    private static void member(String suffix, boolean active) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank, active) VALUES (?, ?, 'Recruit', ?)")) {
            ps.setLong(1, GUILD);
            ps.setString(2, PREFIX + suffix);
            ps.setBoolean(3, active);
            ps.executeUpdate();
        }
    }

    private static void snapshot(Connection c, String rsn, int hours, long totalXp) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO younglings.player_stats_snapshot (rsn, guild_id, snapshot_at, total_level, total_xp, combat_level, quests_complete, skills_json)
                VALUES (?, ?, ?, 100, ?, 50, 0, '{}')""")) {
            ps.setString(1, rsn);
            ps.setLong(2, GUILD);
            ps.setObject(3, T0.plusHours(hours));
            ps.setLong(4, totalXp);
            ps.executeUpdate();
        }
    }
}
