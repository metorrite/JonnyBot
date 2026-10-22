package com.younglings.bot.autopurge;

import com.younglings.bot.autopurge.AutoPurgeRule.Authors;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the auto-clean SQL against the developer's local Postgres (skipped where there isn't one), with a made-up server id. */
class AutoPurgeRepositoryDatabaseTest {
    private static final long GUILD = 987654321012601L;
    private ConnectionSupplier db;
    private AutoPurgeRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new AutoPurgeRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.autopurge_rule WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    @Test
    void filtersAreSavedAndReadBack() {
        repository.save(new AutoPurgeRule(GUILD, 1, 1440, true, Authors.ONLY_BOTS, true, "nitro"));
        AutoPurgeRule read = repository.forGuild(GUILD).get(0);
        assertEquals(Authors.ONLY_BOTS, read.authors());
        assertTrue(read.onlyWithAttachments());
        assertEquals("nitro", read.containsText());
        assertTrue(read.filtered());
    }

    @Test
    void aRuleSavedWithoutFiltersAndARowFromBeforeTheFiltersExistBothMeanDeleteEverythingOld() throws Exception {
        repository.save(new AutoPurgeRule(GUILD, 1, 1440, true));
        // a row written by the old code: the new columns take their defaults
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.autopurge_rule (guild_id, channel_id, max_age_minutes, keep_pinned) VALUES (?, 2, 60, TRUE)")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
        for (AutoPurgeRule rule : repository.forGuild(GUILD)) {
            assertFalse(rule.filtered(), "channel " + rule.channelId());
            assertEquals(Authors.ALL, rule.authors());
            assertFalse(rule.onlyWithAttachments());
            assertNull(rule.containsText());
        }
    }

    @Test
    void changingARuleCanClearItsFilters() {
        repository.save(new AutoPurgeRule(GUILD, 1, 1440, true, Authors.ONLY_HUMANS, true, "x"));
        repository.save(new AutoPurgeRule(GUILD, 1, 1440, true));
        assertFalse(repository.forGuild(GUILD).get(0).filtered());
    }
}
