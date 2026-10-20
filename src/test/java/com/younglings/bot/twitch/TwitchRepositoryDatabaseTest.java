package com.younglings.bot.twitch;

import com.younglings.bot.testsupport.LocalDatabase;
import com.younglings.bot.twitch.TwitchRepository.Settings;
import com.younglings.bot.twitch.TwitchRepository.Streamer;
import com.younglings.bot.twitch.TwitchRules.OfflineAction;
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

/** The Twitch SQL against the developer's own local database. Skipped when there isn't one; uses a made-up server id and removes what it adds. */
class TwitchRepositoryDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_031L;

    private ConnectionSupplier connections;
    private TwitchRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = LocalDatabase.supplier();
        repository = new TwitchRepository(connections);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connections != null) clean();
    }

    private void clean() throws Exception {
        for (String table : new String[]{"twitch_streamer", "twitch_settings"}) {
            try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                s.setLong(1, GUILD);
                s.executeUpdate();
            }
        }
    }

    @Test
    void aServerStartsWithTwitchOffAndAnEditedPostOnceItSwitchesOn() {
        assertEquals(Settings.defaults(), repository.settings(GUILD));
        assertFalse(repository.settings(GUILD).enabled());

        repository.saveSettings(GUILD, new Settings(true, OfflineAction.DELETE));

        assertEquals(new Settings(true, OfflineAction.DELETE), repository.settings(GUILD));
    }

    @Test
    void aStreamerIsFollowedOnceAndOnlyServersThatSwitchedOnArePolled() {
        Streamer bob = repository.add(GUILD, "bob", "Bob", 5L, null, null).orElseThrow();
        assertTrue(repository.add(GUILD, "bob", "Bob", 6L, null, null).isEmpty(), "following twice does nothing");

        assertTrue(repository.active().stream().noneMatch(s -> s.guildId() == GUILD), "switch still off");
        repository.saveSettings(GUILD, new Settings(true, OfflineAction.EDIT));
        assertEquals(1, repository.active().stream().filter(s -> s.guildId() == GUILD).count());

        assertTrue(repository.update(GUILD, bob.id(), 7L, "Hi", 8L, false));
        assertTrue(repository.active().stream().noneMatch(s -> s.guildId() == GUILD), "a streamer switched off is not polled");
        assertFalse(repository.update(GUILD + 1, bob.id(), 7L, null, null, true), "another server cannot change it");
        assertFalse(repository.delete(GUILD + 1, bob.id()));
        assertTrue(repository.delete(GUILD, bob.id()));
    }

    @Test
    void aStreamIsClaimedExactlyOnceAndForgottenWhenItEnds() {
        Streamer bob = repository.add(GUILD, "bob", "Bob", 5L, null, null).orElseThrow();
        repository.prime(bob.id(), null);

        assertTrue(repository.claim(bob.id(), "s1", null), "first claim wins");
        assertFalse(repository.claim(bob.id(), "s1", null), "a second round for the same stream is refused");
        repository.recordMessage(bob.id(), 5L, 99L);
        repository.setMisses(bob.id(), 1);
        Streamer posted = repository.get(GUILD, bob.id()).orElseThrow();
        assertEquals("s1", posted.postedStream());
        assertEquals(99L, posted.postedMessageId());
        assertEquals(1, posted.misses());
        assertTrue(posted.primed());

        repository.clearPosted(bob.id());
        Streamer over = repository.get(GUILD, bob.id()).orElseThrow();
        assertNull(over.postedStream());
        assertNull(over.postedMessageId());
        assertEquals(0, over.misses());
    }
}
