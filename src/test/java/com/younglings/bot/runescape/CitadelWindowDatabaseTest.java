package com.younglings.bot.runescape;

import com.younglings.bot.testsupport.LocalDatabase;
import com.younglings.bot.tracking.CitadelWeek;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cap-at-the-reset problem end to end: activities are saved as the bot saves them, then read back for each Citadel week. Which week a cap belongs
 * to comes from the time it happened, not from when the bot noticed it. Runs over the developer's local Postgres (skipped without one).
 */
class CitadelWindowDatabaseTest {
    private static final long G = 999_000_000_000_000_031L;
    private static final String RSN = "CitadelTest9";
    private static ConnectionSupplier supplier;
    private PlayerLinkRepository links;
    private WeeklyDigestRepository digest;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        var initializer = new RuneScapeDatabaseInitializer(supplier);
        links = new PlayerLinkRepository(supplier, initializer);
        digest = new WeeklyDigestRepository(supplier, initializer);
        cleanUp();
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')")) {
            s.setLong(1, G);
            s.setString(2, RSN);
            s.executeUpdate();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.player_activity WHERE LOWER(rsn) = LOWER(?)")) {
                s.setString(1, RSN);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_member WHERE guild_id = ?")) {
                s.setLong(1, G);
                s.executeUpdate();
            }
        }
    }

    private static PlayerActivity cap(String date) {
        return new PlayerActivity(date, "Capped at my Clan Citadel.", "details");
    }

    @Test
    void aCapJustBeforeTheResetAndOneJustAfterLandInDifferentWeeks() {
        // UK time in October is one hour ahead of UTC: 01:05 is 00:05 UTC (before the 00:10 reset), 01:15 is 00:15 UTC (after it)
        links.saveActivities(RSN, List.of(cap("14-Oct-2026 01:05"), cap("14-Oct-2026 01:15"), cap("13-Oct-2026 23:30")));

        var finished = CitadelWeek.windowEndingOn(LocalDate.of(2026, 10, 14));
        var finishedRows = digest.getCitadelActivityInWindow(G, finished[0], finished[1]);
        assertEquals(List.of("14-Oct-2026 01:05", "13-Oct-2026 23:30"), finishedRows.stream().map(WeeklyDigestRepository.CitadelActivityRow::activityDate)
                .sorted(java.util.Comparator.comparing(RuneMetricsDates::parse).reversed()).toList(), "00:05 and Tuesday 22:30 UTC are in the week that ended at 00:10");

        var next = CitadelWeek.windowEndingOn(LocalDate.of(2026, 10, 21));
        var nextRows = digest.getCitadelActivityInWindow(G, next[0], next[1]);
        assertEquals(1, nextRows.size());
        assertEquals("14-Oct-2026 01:15", nextRows.get(0).activityDate(), "00:15 UTC is in the new week");
    }

    @Test
    void theTimeItHappenedIsStoredAndReadBack() throws Exception {
        links.saveActivities(RSN, List.of(cap("14-Oct-2026 01:05")));
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT occurred_at FROM younglings.player_activity WHERE LOWER(rsn) = LOWER(?)")) {
            s.setString(1, RSN);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(java.time.Instant.parse("2026-10-14T00:05:00Z"), rs.getTimestamp(1).toInstant());
            }
        }
    }

    @Test
    void aRowSavedBeforeTheColumnExistedIsFilledInWhenTheTablesStartUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.player_activity (rsn, activity_date, activity_text, activity_details) VALUES (?, '07-Oct-2026 12:00', 'Capped at my Clan Citadel.', 'old row')")) {
            s.setString(1, RSN);
            s.executeUpdate();
        }
        new RuneScapeDatabaseInitializer(supplier); // the start-up pass fills in rows that have no time yet
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT occurred_at FROM younglings.player_activity WHERE LOWER(rsn) = LOWER(?)")) {
            s.setString(1, RSN);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(java.time.Instant.parse("2026-10-07T11:00:00Z"), rs.getTimestamp(1).toInstant(), "BST: 12:00 written is 11:00 UTC");
            }
        }
    }
}
