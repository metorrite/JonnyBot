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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The beta list's one-time seed from {@code BETA_SEED_GUILDS} over real SQL: a new database is seeded once, a server removed afterwards stays
 * removed, a database seeded before is left alone, and an unset setting writes nothing (not even the marker). Skipped without a local database.
 * The marker table is put back the way it was found.
 */
class BetaSeedDatabaseTest {
    private static final long A = Long.parseLong("6".repeat(18));
    private static final long B = Long.parseLong("7".repeat(19));
    private static ConnectionSupplier supplier;
    private BetaGuildRepository repository;
    private boolean markerWasThere;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void freshDatabase() throws Exception {
        repository = new BetaGuildRepository(supplier, new BetaDatabaseInitializer(supplier));
        markerWasThere = markerExists();
        execute("DROP TABLE IF EXISTS younglings.beta_guild_seeded");
        removeRows();
    }

    @AfterEach
    void putBack() throws Exception {
        removeRows();
        execute("DROP TABLE IF EXISTS younglings.beta_guild_seeded");
        if (markerWasThere) execute("CREATE TABLE IF NOT EXISTS younglings.beta_guild_seeded (done BOOLEAN)");
    }

    @Test
    void aNewDatabaseIsSeededOnceAndARemovalSticks() throws Exception {
        String raw = A + ":Clan A, not-an-id, " + B;

        assertEquals(BetaSeed.Outcome.SEEDED, BetaSeed.seedOnce(supplier, raw));
        assertTrue(markerExists());
        assertEquals(Optional.of("Clan A"), find(A).map(BetaGuildRepository.BetaGuild::label));
        assertEquals(Optional.of(""), find(B).map(BetaGuildRepository.BetaGuild::label));
        assertEquals(AccessLevel.MANAGE_SERVER, find(A).orElseThrow().level(), "seeded rows keep the old rule, Manage Server or above");

        repository.delete(A); // removed on the website
        assertEquals(BetaSeed.Outcome.ALREADY_SEEDED, BetaSeed.seedOnce(supplier, raw));
        assertTrue(find(A).isEmpty());
        assertTrue(find(B).isPresent());
    }

    @Test
    void aDatabaseSeededBeforeIsLeftAlone() throws Exception {
        execute("CREATE TABLE younglings.beta_guild_seeded (done BOOLEAN)"); // what an older build left behind

        assertEquals(BetaSeed.Outcome.ALREADY_SEEDED, BetaSeed.seedOnce(supplier, Long.toString(A)));
        assertTrue(find(A).isEmpty());
    }

    @Test
    void anUnsetOrUnusableSettingWritesNothingSoItCanBeSetLater() throws Exception {
        assertEquals(BetaSeed.Outcome.NOTHING_TO_SEED, BetaSeed.seedOnce(supplier, null));
        assertEquals(BetaSeed.Outcome.NOTHING_TO_SEED, BetaSeed.seedOnce(supplier, "not-an-id"));
        assertFalse(markerExists());

        assertEquals(BetaSeed.Outcome.SEEDED, BetaSeed.seedOnce(supplier, Long.toString(B)));
        assertTrue(find(B).isPresent());
    }

    @Test
    void aServerAlreadyListedKeepsItsLabelAndLevel() throws Exception {
        repository.upsert(A, "set on the website", AccessLevel.OWNER, 7L);

        assertEquals(BetaSeed.Outcome.SEEDED, BetaSeed.seedOnce(supplier, A + ":from the setting"));
        assertEquals("set on the website", find(A).orElseThrow().label());
        assertEquals(AccessLevel.OWNER, find(A).orElseThrow().level());
    }

    private Optional<BetaGuildRepository.BetaGuild> find(long guildId) {
        return repository.all().stream().filter(b -> b.guildId() == guildId).findFirst();
    }

    private static boolean markerExists() throws Exception {
        try (Connection c = supplier.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('younglings.beta_guild_seeded') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static void removeRows() throws Exception {
        try (Connection c = supplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.beta_guild WHERE guild_id IN (?, ?)")) {
            s.setLong(1, A);
            s.setLong(2, B);
            s.executeUpdate();
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = supplier.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
