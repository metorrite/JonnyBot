package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link SiteStatsRepository#pointsByDay} (T-710): clan points per member per day from a date on, one server only. Runs over the local Postgres. */
class PointsByDayDatabaseTest {
    // made-up ids, built at run time
    private static final long GUILD = Long.parseLong("5".repeat(17) + "8");
    private static final long OTHER = Long.parseLong("5".repeat(17) + "9");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    private static ConnectionSupplier db;
    private static SiteStatsRepository stats;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        stats = new SiteStatsRepository(db, new RuneScapeDatabaseInitializer(db));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM younglings.clan_points_award WHERE guild_id IN (" + GUILD + ", " + OTHER + ")");
        }
    }

    private static void award(long guild, String rsn, String type, LocalDate day, long points) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO younglings.clan_points_award (guild_id, rsn, award_type, points, awarded_for_date) VALUES (?, ?, ?, ?, ?)")) {
            p.setLong(1, guild);
            p.setString(2, rsn);
            p.setString(3, type);
            p.setLong(4, points);
            p.setObject(5, day);
            p.executeUpdate();
        }
    }

    @Test
    void sumsEachMembersDayFromTheDateOnInItsOwnServer() throws Exception {
        award(GUILD, "RaceA", "XP", FROM, 10);
        award(GUILD, "RaceA", "CITADEL", FROM, 5);
        award(GUILD, "RaceA", "XP", FROM.plusDays(3), 7);
        award(GUILD, "RaceB", "XP", FROM.minusDays(1), 99); // before the month
        award(OTHER, "RaceA", "XP", FROM, 50);             // another server
        List<SiteStatsRepository.PointsDay> rows = stats.pointsByDay(GUILD, FROM).stream()
                .sorted(Comparator.comparing(SiteStatsRepository.PointsDay::day)).toList();
        assertEquals(List.of(new SiteStatsRepository.PointsDay("RaceA", FROM, 15), new SiteStatsRepository.PointsDay("RaceA", FROM.plusDays(3), 7)), rows);
    }
}
