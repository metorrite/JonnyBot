package com.younglings.bot.runescape;

import com.younglings.bot.testsupport.LocalDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock check reads a fixed number of the newest activities, never the whole table (T-183). Runs against the local database inside a
 * transaction that is rolled back, so nothing it adds is kept.
 */
class RuneMetricsClockCheckDatabaseTest {

    @Test
    void theCheckReadsTheNewestRowsByIdInsteadOfScanningTheTable() throws Exception {
        try (Connection c = LocalDatabase.supplier().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS younglings");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS younglings.player_activity (
                            id BIGSERIAL PRIMARY KEY, guild_id BIGINT, rsn TEXT NOT NULL, activity_date TEXT NOT NULL, activity_text TEXT NOT NULL,
                            activity_details TEXT NOT NULL, recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), occurred_at TIMESTAMPTZ)
                        """);
                s.execute("ALTER TABLE younglings.player_activity ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ");
                // a long history (a month old) and a few recent rows, one of which "happened after we saw it"
                s.execute("""
                        INSERT INTO younglings.player_activity (guild_id, rsn, activity_date, activity_text, activity_details, recorded_at, occurred_at)
                        SELECT -183, 't183 ' || g, 'd' || g, 'old', '', NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days'
                        FROM generate_series(1, 60000) g
                        """);
                s.execute("""
                        INSERT INTO younglings.player_activity (guild_id, rsn, activity_date, activity_text, activity_details, recorded_at, occurred_at)
                        SELECT -183, 't183 new ' || g, 'n' || g, 'new', '', NOW(), CASE WHEN g = 1 THEN NOW() + INTERVAL '1 hour' ELSE NOW() END
                        FROM generate_series(1, 30) g
                        """);
                s.execute("ANALYZE younglings.player_activity");

                StringBuilder plan = new StringBuilder();
                try (ResultSet rs = s.executeQuery("EXPLAIN " + RuneMetricsClockCheck.SQL)) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                }
                assertFalse(plan.toString().contains("Seq Scan on player_activity"), "the check must not read the whole table:\n" + plan);
                assertTrue(plan.toString().contains("Index Scan Backward"), "the newest rows come off the primary key:\n" + plan);

                try (ResultSet rs = s.executeQuery(RuneMetricsClockCheck.SQL)) {
                    assertTrue(rs.next());
                    assertTrue(rs.getLong("total") >= 30, "the recent rows are counted");
                    assertTrue(rs.getLong("total") <= RuneMetricsClockCheck.MAX_ROWS);
                    assertEquals(1, rs.getLong("early") - countOtherEarly(c), "the one early row is seen");
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** Early rows that were already in a developer's database before the test added its own (0 on a fresh one). */
    private static long countOtherEarly(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT COUNT(*) FROM (SELECT occurred_at, recorded_at, guild_id FROM younglings.player_activity ORDER BY id DESC LIMIT %d) recent
                WHERE recorded_at >= NOW() - INTERVAL '3 days' AND occurred_at > recorded_at + INTERVAL '2 minutes' AND guild_id IS DISTINCT FROM -183
                """.formatted(RuneMetricsClockCheck.MAX_ROWS))) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
