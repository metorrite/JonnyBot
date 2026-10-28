package com.runeclans.bot.beta;

import com.runeclans.bot.access.AccessLevel;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The beta list's per-server level over real SQL (T-345): old rows keep Manage Server, a saved level sticks, an unknown one lets nobody in. Skipped without a local database. */
class BetaGuildRepositoryDatabaseTest {
    private static final long OLD_ROW = 999_000_000_000_000_345L;
    private static final long NEW_ROW = 999_000_000_000_000_346L;
    private static ConnectionSupplier supplier;
    private BetaGuildRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new BetaGuildRepository(supplier, new BetaDatabaseInitializer(supplier));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.beta_guild WHERE guild_id IN (?, ?)")) {
            s.setLong(1, OLD_ROW);
            s.setLong(2, NEW_ROW);
            s.executeUpdate();
        }
    }

    private BetaGuildRepository.BetaGuild find(long guildId) {
        return repository.all().stream().filter(b -> b.guildId() == guildId).findFirst().orElseThrow();
    }

    private void insertRaw(String sql, long guildId) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.executeUpdate();
        }
    }

    @Test
    void aRowWrittenBeforeLevelsExistedKeepsManageServer() throws Exception {
        insertRaw("INSERT INTO younglings.beta_guild (guild_id, label) VALUES (?, 'old')", OLD_ROW);
        new BetaDatabaseInitializer(supplier); // running the migration again changes nothing

        assertEquals(AccessLevel.MANAGE_SERVER, find(OLD_ROW).level());
    }

    @Test
    void aSavedLevelSticksAndCanBeChanged() {
        repository.upsert(NEW_ROW, "new", AccessLevel.ADMINISTRATOR, 7L);
        assertEquals(AccessLevel.ADMINISTRATOR, find(NEW_ROW).level());

        repository.upsert(NEW_ROW, "renamed", AccessLevel.OWNER, 7L);
        assertEquals(AccessLevel.OWNER, find(NEW_ROW).level());
        assertEquals("renamed", find(NEW_ROW).label());
    }

    @Test
    void aLevelTheCodeDoesNotKnowReadsAsNone() throws Exception {
        insertRaw("INSERT INTO younglings.beta_guild (guild_id, label, min_level) VALUES (?, 'odd', 'moderator')", NEW_ROW);

        assertNull(find(NEW_ROW).level());
    }
}
