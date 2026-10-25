package com.younglings.bot.clanprofile;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changing a site's address (D4) over a real database (skipped without one), with made-up server ids and a clock the test moves. */
class SlugChangeServiceDatabaseTest {
    private static final long CLAN = 987654321031001L;
    private static final long OTHER = 987654321031002L;
    private ConnectionSupplier db;
    private ClanProfileRepository profiles;
    private SlugChangeService service;
    private Instant now = Instant.parse("2026-03-01T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        profiles = new ClanProfileRepository(db);
        service = new SlugChangeService(profiles, new SlugChangeRepository(db));
        service.clock = () -> now;
        clean();
        profiles.saveContent(profile(CLAN, "d4-start"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String sql : new String[]{"DELETE FROM younglings.clan_profile WHERE guild_id IN (?, ?)", "DELETE FROM younglings.clan_slug_hold WHERE guild_id IN (?, ?)",
                    "DELETE FROM younglings.clan_slug_change WHERE guild_id IN (?, ?)"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, CLAN);
                    s.setLong(2, OTHER);
                    s.executeUpdate();
                }
            }
        }
    }

    private static ClanProfile profile(long guildId, String slug) {
        return new ClanProfile(guildId, ClanProfile.Visibility.PUBLIC, slug, "Test clan", "", "", null, null, null, Set.of(), ClanProfile.Recruiting.CLOSED, "", null,
                false, null, null, null, null, null, null);
    }

    private String slug() {
        return profiles.get(CLAN).orElseThrow().slug();
    }

    @Test
    void theAddressChangesAfterTheHourAndOnlyThen() {
        assertTrue(service.request(CLAN, "d4-next").ok());
        assertEquals("d4-start", slug(), "nothing changes during the hour");
        now = now.plus(Duration.ofMinutes(59));
        assertEquals("d4-next", service.status(CLAN).pendingSlug());
        assertEquals("d4-start", slug());
        now = now.plus(Duration.ofMinutes(2));
        assertNull(service.status(CLAN).pendingSlug(), "reading applies a request whose hour is over");
        assertEquals("d4-next", slug());
        assertTrue(profiles.slugTaken("d4-start", OTHER), "the old address is held and forwards for 30 days");
    }

    @Test
    void lockInChangesItAtOnceThenTheCooldownRefusesTheNextChange() {
        service.request(CLAN, "d4-next");
        assertTrue(service.lockIn(CLAN).ok());
        assertEquals("d4-next", slug());
        var refused = service.request(CLAN, "d4-third");
        assertFalse(refused.ok());
        assertNotNull(service.status(CLAN).cooldownUntil());
        now = now.plus(Duration.ofDays(31));
        assertTrue(service.request(CLAN, "d4-third").ok());
    }

    @Test
    void changingTheRequestDuringTheHourKeepsTheOriginalClockAndCancelDropsIt() {
        service.request(CLAN, "d4-one");
        var opened = service.status(CLAN).requestedAt();
        now = now.plus(Duration.ofMinutes(30));
        service.request(CLAN, "d4-two");
        assertEquals(opened, service.status(CLAN).requestedAt());
        assertEquals("d4-two", service.status(CLAN).pendingSlug());
        service.cancel(CLAN);
        assertNull(service.status(CLAN).pendingSlug());
        assertNull(service.status(CLAN).cooldownUntil(), "a cancelled request starts no cooldown");
        assertFalse(service.lockIn(CLAN).ok());
    }

    @Test
    void refusesTakenReservedAndOffensiveAddresses() {
        profiles.saveContent(profile(OTHER, "d4-taken"));
        assertFalse(service.request(CLAN, "d4-taken").ok());
        assertFalse(service.request(CLAN, "search").ok());
        assertFalse(service.request(CLAN, "big-porn").ok());
        assertFalse(service.request(CLAN, "d4-start").ok(), "the address it already has");
        assertNull(service.status(CLAN).pendingSlug());
    }

    @Test
    void anAddressTakenDuringTheHourDropsTheChange() {
        service.request(CLAN, "d4-race");
        profiles.saveContent(profile(OTHER, "d4-race"));
        var result = service.lockIn(CLAN);
        assertFalse(result.ok());
        assertEquals("d4-start", slug());
        assertNull(service.status(CLAN).pendingSlug());
    }
}
