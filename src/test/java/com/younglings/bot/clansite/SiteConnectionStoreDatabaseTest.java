package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteConnectionStore.Channel;
import com.younglings.bot.clansite.SiteConnectionStore.Connection;
import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.younglings.bot.internal.SiteNewsService;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site's connections over real SQL (N7): the one-time copy of the bot dashboard's Discord website settings, which never overwrites a later
 * edit; saving the Discord features; and connecting or disconnecting the server. Skipped without a local database.
 */
class SiteConnectionStoreDatabaseTest {
    private static final long COPIED = Long.parseLong("9".repeat(17)) + 457;
    private static final long FRESH = COPIED + 1;
    private static final long CHANNEL = Long.parseLong("8".repeat(18));
    private static final long OTHER_CHANNEL = CHANNEL + 1;
    private static final long ADMIN = Long.parseLong("4".repeat(18));
    private static ConnectionSupplier supplier;
    private SiteDiscordSettings discordSettings;
    private SiteNewsService news;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        discordSettings = new SiteDiscordSettings(supplier);
        news = new SiteNewsService(supplier);
        new SiteConnectionStore(supplier, discordSettings, news); // makes the tables
        cleanUp();
        // what the bot dashboard stored before connections existed
        sql("INSERT INTO younglings.site_discord_setting (guild_id, online_mode) VALUES (?, 'names+avatars')", COPIED);
        news.replaceChannels(COPIED, List.of(new SiteNewsService.NewsChannel(CHANNEL, "Announcements", 0)));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"site_connection", "site_connection_channel", "site_discord_setting", "site_news_channel"}) {
            try (java.sql.Connection c = supplier.getConnection();
                 PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                s.setLong(1, COPIED);
                s.setLong(2, FRESH);
                s.executeUpdate();
            }
        }
    }

    private static void sql(String sql, long guildId) throws Exception {
        try (java.sql.Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.executeUpdate();
        }
    }

    @Test
    void theOneTimeCopyBringsTheDashboardsChoicesOverAndNeverAgain() {
        SiteConnectionStore store = new SiteConnectionStore(supplier, discordSettings, news);

        Connection copied = store.of(COPIED);
        assertTrue(copied.copiedFromBot());
        assertTrue(copied.connected());
        assertEquals(OnlineMode.NAMES_AVATARS, copied.onlineMode(), "N1: Names and avatars survives the move");
        assertEquals(List.of(new Channel(CHANNEL, "Announcements", 0)), copied.channels());

        // the admin turns the online list off and drops the channel here; the next start must not copy the old choices back
        store.saveFeatures(copied.withFeatures(OnlineMode.OFF, 10, false, 3, false, List.of()), ADMIN);
        news.replaceChannels(COPIED, List.of(new SiteNewsService.NewsChannel(OTHER_CHANNEL, null, 0))); // the home site's news, edited elsewhere
        SiteConnectionStore restarted = new SiteConnectionStore(supplier, discordSettings, news);

        Connection after = restarted.of(COPIED);
        assertEquals(OnlineMode.OFF, after.onlineMode());
        assertEquals(10, after.onlineLimit());
        assertFalse(after.groupByRole());
        assertEquals(3, after.feedLimit());
        assertFalse(after.eventsEnabled());
        assertTrue(after.channels().isEmpty());
    }

    @Test
    void aSiteWithNothingStoredGetsTheDefaults() {
        Connection fresh = new SiteConnectionStore(supplier, discordSettings, news).of(FRESH);

        assertTrue(fresh.connected(), "a site starts connected to the server it was made for");
        assertEquals(SiteConnectionStore.RUNECLAN_BOT, fresh.providerId());
        assertEquals(OnlineMode.OFF, fresh.onlineMode());
        assertEquals(SiteConnectionStore.DEFAULT_ONLINE_LIMIT, fresh.onlineLimit());
        assertTrue(fresh.eventsEnabled());
        assertTrue(fresh.channels().isEmpty());
        assertFalse(fresh.copiedFromBot());
    }

    @Test
    void featuresAndTheConnectionAreKeptApart() {
        SiteConnectionStore store = new SiteConnectionStore(supplier, discordSettings, news);
        store.saveFeatures(store.of(FRESH).withFeatures(OnlineMode.COUNT, 24, true, 5, true,
                List.of(new Channel(OTHER_CHANNEL, null, 0), new Channel(CHANNEL, "News", 1))), ADMIN);

        store.setConnected(FRESH, false, ADMIN);
        Connection off = store.of(FRESH);
        assertFalse(off.connected());
        assertNull(off.connectedBy());
        assertEquals(OnlineMode.COUNT, off.onlineMode(), "disconnecting keeps the features for later");
        assertEquals(List.of(OTHER_CHANNEL, CHANNEL), off.channels().stream().map(Channel::channelId).toList(), "in the admin's order");

        store.setConnected(FRESH, true, ADMIN);
        Connection on = store.of(FRESH);
        assertTrue(on.connected());
        assertEquals(ADMIN, on.connectedBy());
        assertTrue(on.connectedAt() != null);
    }
}
