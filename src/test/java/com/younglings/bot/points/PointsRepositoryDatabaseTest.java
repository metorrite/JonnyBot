package com.younglings.bot.points;

import com.younglings.bot.points.PointsRepository.Settings;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs the repository's SQL against the developer's local Postgres (skipped where there isn't one). */
class PointsRepositoryDatabaseTest {
    private static final long GUILD = 987654321012399L;
    private ConnectionSupplier db;
    private PointsRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new PointsRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.points_settings WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    @Test
    void aMissingReviewChannelStaysNullInsteadOfBecomingZero() {
        repository.saveSettings(new Settings(GUILD, true, "points", null));

        Settings read = repository.settings(GUILD);

        assertNull(read.reviewChannelId());
        assertEquals("points", read.unit());
    }

    @Test
    void aSavedReviewChannelIsReadBack() {
        repository.saveSettings(new Settings(GUILD, true, "points", 4242L));

        assertEquals(4242L, repository.settings(GUILD).reviewChannelId());
    }
}
