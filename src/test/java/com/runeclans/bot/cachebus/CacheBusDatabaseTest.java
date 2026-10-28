package com.runeclans.bot.cachebus;

import com.runeclans.bot.automod.AutomodRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two "processes" (each its own listener, dispatcher and caches) on one real Postgres: a {@code NOTIFY} sent by one drops the entry in the
 * other. Skipped without a scratch database ({@code RUNECLAN_BOT_TEST_DATABASE_URL}).
 */
class CacheBusDatabaseTest {
    private ConnectionSupplier db;
    private final List<CacheListener> listeners = new ArrayList<>();
    private long guild;
    private boolean savedRow;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        // a made-up server id, built at runtime
        guild = Long.parseLong("7".repeat(18)) - ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }

    @AfterEach
    void tearDown() throws SQLException {
        listeners.forEach(CacheListener::stop);
        if (db == null || !savedRow) return;
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.automod_config WHERE guild_id = ?")) {
            s.setLong(1, guild);
            s.executeUpdate();
        }
    }

    private CacheListener listen(String name, CacheInvalidator... caches) {
        CacheListener listener = new CacheListener(() -> {
            Connection c = db.getConnection();
            try (Statement s = c.createStatement()) {
                s.execute("SET application_name = 'cache-bus-test-" + name + "'");
            }
            return c;
        }, new CacheDispatcher(List.of(caches)), BotScheduler.lane("cache-bus-test-" + name));
        listeners.add(listener);
        listener.start();
        waitFor(listener::listening, "listener " + name + " to connect");
        return listener;
    }

    private void send(String payload) throws SQLException {
        try (Connection c = db.getConnection()) {
            CacheBus.notify(c, payload);
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting for " + what);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void aSignalFromOneProcessEvictsInTheOther() throws Exception {
        CacheDispatcherTest.Recording first = new CacheDispatcherTest.Recording("automod");
        CacheDispatcherTest.Recording second = new CacheDispatcherTest.Recording("automod");
        CacheDispatcherTest.Recording otherArea = new CacheDispatcherTest.Recording("afk");
        listen("a", first);
        listen("b", second, otherArea);

        send("automod:" + guild);
        waitFor(() -> second.evicted.contains(guild), "process b to drop the guild");
        waitFor(() -> first.evicted.contains(guild), "process a to hear its own signal too (eviction is idempotent)");
        send("junk-without-a-guild");
        send("afk:" + guild);
        waitFor(() -> otherArea.evicted.contains(guild), "the afk area");
        assertEquals(List.of(guild), second.evicted);
    }

    @Test
    void aSettingSavedOnOneProcessIsReadFreshOnTheOther() throws Exception {
        AutomodRepository api = new AutomodRepository(db);
        AutomodRepository gateway = new AutomodRepository(db);
        listen("api", api);
        listen("gateway", gateway);

        assertFalse(gateway.get(guild).enabled(), "the gateway caches the default (off)");
        AutomodRepository.Config on = new AutomodRepository.Config(guild, true, Set.of(), Set.of(), AutomodRepository.Config.defaults(guild).rules());
        savedRow = true;
        api.save(on);
        assertTrue(api.get(guild).enabled(), "the writer updates its own cache at once, as before");
        assertFalse(gateway.get(guild).enabled(), "without a signal the gateway still has the old entry");

        // what CacheBus.changed sends after the save when the bus is on
        send("automod:" + guild);
        waitFor(() -> gateway.get(guild).enabled(), "the gateway to read the saved setting");
        assertTrue(api.get(guild).enabled(), "the writer reloads the same row after its own signal");
    }

    @Test
    void aSaveSendsItsOwnSignalThroughTheBus() throws Exception {
        AutomodRepository api = new AutomodRepository(db);
        AutomodRepository gateway = new AutomodRepository(db);
        listen("api-bus", api);
        listen("gateway-bus", gateway);
        assertFalse(gateway.get(guild).enabled(), "the gateway caches the default (off)");

        CacheBus.Sender previous = CacheBus.swapSender(new CacheBus.Sender(db, BotScheduler.lane("cache-bus-test-send")));
        try {
            AutomodRepository.Config on = new AutomodRepository.Config(guild, true, Set.of(), Set.of(), AutomodRepository.Config.defaults(guild).rules());
            savedRow = true;
            // the real path: save, then CacheBus.changed queues pg_notify on the send lane; nothing here sends by hand
            api.save(on);
            waitFor(() -> gateway.get(guild).enabled(), "the gateway to read the saved setting");
            assertTrue(api.get(guild).enabled(), "the writer reloads the same row after its own signal");
        } finally {
            CacheBus.swapSender(previous);
        }
    }

    @Test
    void aDroppedConnectionReconnectsAndClearsEverything() throws Exception {
        CacheDispatcherTest.Recording cache = new CacheDispatcherTest.Recording("automod");
        CacheListener listener = listen("drop", cache);
        waitFor(() -> cache.cleared == 1, "the first connect to clear the cache");

        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'cache-bus-test-drop'");
        }
        waitFor(() -> cache.cleared == 2, "the reconnect to clear the cache again");
        waitFor(listener::listening, "the listener to be back");

        send("automod:" + guild);
        waitFor(() -> cache.evicted.contains(guild), "a signal after the reconnect");
    }
}
