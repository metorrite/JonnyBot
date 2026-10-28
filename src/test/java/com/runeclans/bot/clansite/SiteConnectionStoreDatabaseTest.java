package com.runeclans.bot.clansite;

import com.runeclans.bot.clansite.SiteConnectionStore.Channel;
import com.runeclans.bot.clansite.SiteConnectionStore.Connection;
import com.runeclans.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.runeclans.bot.internal.SiteNewsService;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.testsupport.SiteMappings;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site's connections over real SQL (N7): the one-time copy of the bot dashboard's Discord website settings, which never overwrites a later
 * edit; saving the Discord features and the admin's yes for private channels; and connecting or disconnecting the server. Skipped without a
 * local database.
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
        news = new SiteNewsService(supplier, SiteMappings.off(supplier));
        new SiteConnectionStore(supplier, discordSettings, news, SiteMappings.off(supplier)); // makes the tables
        cleanUp();
        // what the bot dashboard stored before connections existed
        sql("INSERT INTO younglings.site_discord_setting (guild_id, online_mode) VALUES (?, 'names+avatars')", COPIED);
        news.replaceChannels(COPIED, List.of(new SiteNewsService.NewsChannel(CHANNEL, "Announcements", 0)));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"site_connection", "site_connection_channel", "site_private_channel_ok", "site_discord_setting", "site_news_channel"}) {
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
        SiteConnectionStore store = new SiteConnectionStore(supplier, discordSettings, news, SiteMappings.off(supplier));

        Connection copied = store.of(COPIED);
        assertTrue(copied.copiedFromBot());
        assertTrue(copied.connected());
        assertEquals(OnlineMode.NAMES_AVATARS, copied.onlineMode(), "N1: Names and avatars survives the move");
        assertEquals(List.of(new Channel(CHANNEL, "Announcements", 0)), copied.channels());

        // the admin turns the online list off and drops the channel here; the next start must not copy the old choices back
        store.saveFeatures(copied.withFeatures(OnlineMode.OFF, 10, false, 3, false, List.of()), ADMIN);
        news.replaceChannels(COPIED, List.of(new SiteNewsService.NewsChannel(OTHER_CHANNEL, null, 0))); // the home site's news, edited elsewhere
        SiteConnectionStore restarted = new SiteConnectionStore(supplier, discordSettings, news, SiteMappings.off(supplier));

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
        Connection fresh = new SiteConnectionStore(supplier, discordSettings, news, SiteMappings.off(supplier)).of(FRESH);

        assertTrue(fresh.connected(), "a site starts connected to the server it was made for");
        assertEquals(SiteConnectionStore.RUNECLAN_BOT, fresh.providerId());
        assertEquals(OnlineMode.OFF, fresh.onlineMode());
        assertEquals(SiteConnectionStore.DEFAULT_ONLINE_LIMIT, fresh.onlineLimit());
        assertTrue(fresh.eventsEnabled());
        assertTrue(fresh.channels().isEmpty());
        assertFalse(fresh.copiedFromBot());
    }

    @Test
    void theYesForAPrivateChannelLastsWhileItIsChosenAndKeepsWhoGaveIt() throws Exception {
        SiteConnectionStore store = new SiteConnectionStore(supplier, discordSettings, news, SiteMappings.off(supplier));
        Connection fresh = store.of(FRESH);
        List<Channel> both = List.of(new Channel(CHANNEL, null, 0), new Channel(OTHER_CHANNEL, null, 1));
        assertTrue(store.privateOk(FRESH).isEmpty());

        store.saveFeatures(fresh.withFeatures(OnlineMode.OFF, 24, true, 5, true, both), ADMIN, Set.of(CHANNEL));
        assertEquals(Set.of(CHANNEL), store.privateOk(FRESH));

        // another admin saves other settings (no channel list): the yes stays, with who gave it
        long other = ADMIN + 1;
        store.saveFeatures(store.of(FRESH).withFeatures(OnlineMode.COUNT, 24, true, 5, true, both), other);
        assertEquals(Set.of(CHANNEL), store.privateOk(FRESH));
        // and saving the list again with the same yes keeps the first approver
        store.saveFeatures(store.of(FRESH).withFeatures(OnlineMode.COUNT, 24, true, 5, true, both), other, Set.of(CHANNEL));
        try (java.sql.Connection c = supplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT approved_by FROM younglings.site_private_channel_ok WHERE guild_id = ? AND channel_id = ?")) {
            s.setLong(1, FRESH);
            s.setLong(2, CHANNEL);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(ADMIN, rs.getLong(1));
            }
        }

        // a yes for a channel that isn't chosen is never stored; dropping the channel drops its yes
        store.saveFeatures(store.of(FRESH).withFeatures(OnlineMode.COUNT, 24, true, 5, true, List.of(new Channel(OTHER_CHANNEL, null, 0))), ADMIN,
                Set.of(CHANNEL, OTHER_CHANNEL + 1));
        assertTrue(store.privateOk(FRESH).isEmpty());
        store.saveFeatures(store.of(FRESH).withFeatures(OnlineMode.COUNT, 24, true, 5, true, both), ADMIN, Set.of(OTHER_CHANNEL));
        assertEquals(Set.of(OTHER_CHANNEL), store.privateOk(FRESH), "the yes has to be given again after a channel was dropped");
        assertTrue(store.privateOk(COPIED).isEmpty(), "yeses are per site");
    }

    @Test
    void featuresAndTheConnectionAreKeptApart() {
        SiteConnectionStore store = new SiteConnectionStore(supplier, discordSettings, news, SiteMappings.off(supplier));
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
