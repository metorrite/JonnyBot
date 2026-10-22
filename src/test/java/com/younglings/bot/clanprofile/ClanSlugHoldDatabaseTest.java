package com.younglings.bot.clanprofile;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The address grace period (T-290) against the developer's local Postgres (skipped where there isn't one), with made-up server ids. */
class ClanSlugHoldDatabaseTest {
    private static final long CLAN_A = 987654321029001L;
    private static final long CLAN_B = 987654321029002L;
    private ConnectionSupplier db;
    private ClanProfileRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new ClanProfileRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String sql : new String[] {"DELETE FROM younglings.clan_profile WHERE guild_id IN (?, ?)", "DELETE FROM younglings.clan_slug_hold WHERE guild_id IN (?, ?)"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, CLAN_A);
                    s.setLong(2, CLAN_B);
                    s.executeUpdate();
                }
            }
        }
    }

    private static ClanProfile profile(long guildId, String slug) {
        return new ClanProfile(guildId, ClanProfile.Visibility.PUBLIC, slug, "Test clan", "", "", null, null, null, Set.of(), ClanProfile.Recruiting.CLOSED, "", null,
                false, null, null, null, null, null, null);
    }

    @Test
    void aGivenUpAddressIsHeldForTheClanThatHadIt() {
        repository.saveContent(profile(CLAN_A, "t290-alpha"));
        assertFalse(repository.slugHolder("t290-alpha").isPresent(), "an address in use isn't 'held'");

        repository.saveContent(profile(CLAN_A, "t290-beta"));
        assertEquals(Optional.of(CLAN_A), repository.slugHolder("t290-alpha"));
        assertTrue(repository.slugTaken("t290-alpha", CLAN_B), "another clan can't take it during the grace period");
        assertFalse(repository.slugTaken("t290-alpha", CLAN_A), "the clan that gave it up can take it back");
        assertTrue(repository.slugTaken("t290-beta", CLAN_B), "the new address is in use");
    }

    @Test
    void takingAnAddressBackEndsItsHold() {
        repository.saveContent(profile(CLAN_A, "t290-alpha"));
        repository.saveContent(profile(CLAN_A, "t290-beta"));
        repository.saveContent(profile(CLAN_A, "t290-alpha"));
        assertFalse(repository.slugHolder("t290-alpha").isPresent(), "back in use, so not held");
        assertEquals(Optional.of(CLAN_A), repository.slugHolder("t290-beta"), "the one it just left is held now");
    }

    @Test
    void aHoldEndsAfterTheGracePeriod() throws Exception {
        repository.saveContent(profile(CLAN_A, "t290-alpha"));
        repository.saveContent(profile(CLAN_A, "t290-beta"));
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.clan_slug_hold SET released_at = NOW() - INTERVAL '31 days' WHERE guild_id = ?")) {
            s.setLong(1, CLAN_A);
            s.executeUpdate();
        }
        assertFalse(repository.slugTaken("t290-alpha", CLAN_B), "free for anyone after the grace period");
        assertFalse(repository.slugHolder("t290-alpha").isPresent());
        // and it is cleared away on the next save
        repository.saveContent(profile(CLAN_B, "t290-alpha"));
        assertFalse(repository.slugTaken("t290-alpha", CLAN_B));
        assertTrue(repository.slugTaken("t290-alpha", CLAN_A), "clan B has it now");
    }

    @Test
    void savingWithTheSameAddressHoldsNothing() {
        repository.saveContent(profile(CLAN_A, "t290-alpha"));
        repository.saveContent(profile(CLAN_A, "t290-alpha"));
        assertFalse(repository.slugHolder("t290-alpha").isPresent());
    }
}
