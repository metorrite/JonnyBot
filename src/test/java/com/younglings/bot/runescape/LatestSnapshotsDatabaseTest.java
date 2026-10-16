package com.younglings.bot.runescape;

import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the latest stats of a whole roster at once (what the team builder uses for each player's strength), over the developer's local Postgres (skipped
 * without one). It only touches rows for made-up names that start with "zz team test", which it removes afterwards.
 */
class LatestSnapshotsDatabaseTest {
    private static ConnectionSupplier supplier;
    private PlayerLinkRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        repository = new PlayerLinkRepository(supplier, new RuneScapeDatabaseInitializer(supplier));
        clean();
    }

    @AfterEach
    void clean() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.player_stats_snapshot WHERE LOWER(rsn) LIKE 'zz team test %'")) {
            s.executeUpdate();
        }
    }

    private static RuneScapeProfile profile(int totalLevel, long totalXp, int combatLevel) {
        return new RuneScapeProfile("x", totalLevel, totalXp, combatLevel, 0, 0, 0, List.of(), List.of());
    }

    @Test
    void theLatestSnapshotOfEachNameComesBackInOneQueryWhateverTheCase() {
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveSnapshotAt("zz Team Test Main", now.minusDays(2), profile(2000, 100L, 130), "[]");
        repository.saveSnapshotAt("ZZ team test MAIN", now.minusDays(1), profile(2100, 150L, 138), "[]");
        repository.saveSnapshotAt("zz team test alt", now.minusHours(3), profile(900, 50L, 90), "[]");

        Map<String, StatsSnapshotRow> latest = repository.getLatestSnapshots(List.of("ZZ TEAM TEST MAIN", "zz team test alt", "zz team test nobody"));

        assertEquals(2, latest.size(), "a name that was never polled is simply missing");
        assertEquals(150L, latest.get("zz team test main").totalXp(), "the newest of the two snapshots of the main, whatever the case it was saved in");
        assertEquals(2100, latest.get("zz team test main").totalLevel());
        assertEquals(138, latest.get("zz team test main").combatLevel());
        assertEquals(50L, latest.get("zz team test alt").totalXp());
        assertFalse(latest.containsKey("zz team test nobody"));
        assertTrue(repository.getLatestSnapshots(List.of()).isEmpty());
    }
}
