package com.younglings.bot.hubsites;

import com.younglings.bot.clansite.SiteConnectionStore;
import com.younglings.bot.clansite.SiteDiscordSettings;
import com.younglings.bot.internal.CommunitySettings;
import com.younglings.bot.internal.SiteNewsService;
import com.younglings.bot.testsupport.LocalDatabase;
import com.younglings.bot.testsupport.SiteMappings;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel picks as mappings of the hub site (T-505), with a stub hub: seeded from the bot's own tables the first time, then read and written
 * by hub site id; the bot's own tables untouched with the switch off, for a server with no hub site, and after the move.
 */
class SiteChannelMappingsDatabaseTest {
    // fake ids, made at runtime
    private static final long GUILD = Long.parseLong("9".repeat(17)) + 505;
    private static final long OTHER_GUILD = GUILD + 1;
    private static final long NEWS = Long.parseLong("8".repeat(18));
    private static final long FEED = NEWS + 1;
    private static final long POLL = NEWS + 2;
    private static final long PICKED = NEWS + 3;
    private static final String SITE = "site_t505_test";
    private static ConnectionSupplier db;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        // the bot's own tables, and what the server picked there before the move
        SiteNewsService ownNews = new SiteNewsService(db, SiteMappings.off(db));
        new SiteConnectionStore(db, new SiteDiscordSettings(db), ownNews, SiteMappings.off(db));
        new CommunitySettings(db, SiteMappings.off(db));
        new SiteChannelMappings(db, HubSites.off());
        cleanUp();
        sql("INSERT INTO younglings.site_news_channel (guild_id, channel_id, label, position) VALUES (?, " + NEWS + ", 'News', 0)", GUILD);
        sql("INSERT INTO younglings.site_connection (guild_id, discord_guild_id) VALUES (?, " + GUILD + ")", GUILD); // so the connections' own one-time copy skips it
        sql("INSERT INTO younglings.site_connection_channel (guild_id, channel_id, label, position) VALUES (?, " + FEED + ", NULL, 0)", GUILD);
        sql("INSERT INTO younglings.site_setting (guild_id, poll_channel_id) VALUES (?, " + POLL + ")", GUILD);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : List.of("site_news_channel", "site_connection_channel", "site_connection", "site_setting")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id IN (?, " + OTHER_GUILD + ")", GUILD);
        }
        try (var c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_site_channel WHERE site_id = ?");
             PreparedStatement m = c.prepareStatement("DELETE FROM younglings.hub_site_mapping WHERE site_id = ?")) {
            s.setString(1, SITE);
            s.executeUpdate();
            m.setString(1, SITE);
            m.executeUpdate();
        }
    }

    private static void sql(String statement, long guildId) throws Exception {
        try (var c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.setLong(1, guildId);
            s.executeUpdate();
        }
    }

    private static int ownNewsRows() throws Exception {
        try (var c = db.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM younglings.site_news_channel WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void theNewsChannelMappingResolvesByHubSiteId() throws Exception {
        HubSitesTest.Stub hub = new HubSitesTest.Stub().site(GUILD, SITE);
        SiteChannelMappings mappings = new SiteChannelMappings(db, HubSites.on(hub));
        SiteNewsService news = new SiteNewsService(db, mappings);

        // the first lookup copies the server's picks in, so the switch changes nothing on the site
        assertEquals(List.of(new SiteNewsService.NewsChannel(NEWS, "News", 0)), news.channels(GUILD));
        assertEquals(Optional.of(SITE), mappings.siteFor(GUILD));

        // edits now land on the mapping, keyed by the hub site, and the bot's own table keeps its old rows
        news.replaceChannels(GUILD, List.of(new SiteNewsService.NewsChannel(PICKED, null, 0)));
        assertEquals(List.of(new SiteChannelMappings.Mapped(PICKED, null, 0)), mappings.channels(GUILD, SiteChannelMappings.Kind.NEWS).orElseThrow());
        assertEquals(1, ownNewsRows());

        // seeding runs once: a restart (a new store) doesn't copy the old picks back over the edit
        SiteNewsService restarted = new SiteNewsService(db, new SiteChannelMappings(db, HubSites.on(hub)));
        assertEquals(PICKED, restarted.channels(GUILD).get(0).channelId());
    }

    @Test
    void feedAndPollChannelsAreMappedToo() throws Exception {
        SiteChannelMappings mappings = new SiteChannelMappings(db, HubSites.on(new HubSitesTest.Stub().site(GUILD, SITE)));
        SiteNewsService news = new SiteNewsService(db, mappings);
        SiteConnectionStore connections = new SiteConnectionStore(db, new SiteDiscordSettings(db), news, mappings);
        CommunitySettings community = new CommunitySettings(db, mappings);

        assertEquals(List.of(new SiteConnectionStore.Channel(FEED, null, 0)), connections.of(GUILD).channels());
        assertEquals(POLL, community.pollChannel(GUILD));

        var now = connections.of(GUILD);
        connections.saveFeatures(now.withFeatures(now.onlineMode(), now.onlineLimit(), now.groupByRole(), now.feedLimit(), now.eventsEnabled(),
                List.of(new SiteConnectionStore.Channel(PICKED, "Picked", 0))), 1L);
        community.setPollChannel(GUILD, null);
        assertEquals(List.of(new SiteConnectionStore.Channel(PICKED, "Picked", 0)), connections.of(GUILD).channels());
        assertNull(community.pollChannel(GUILD));

        // the bot's own tables are as they were
        CommunitySettings own = new CommunitySettings(db, SiteMappings.off(db));
        assertEquals(POLL, own.pollChannel(GUILD));
        assertEquals(List.of(new SiteConnectionStore.Channel(FEED, null, 0)),
                new SiteConnectionStore(db, new SiteDiscordSettings(db), news, SiteMappings.off(db)).of(GUILD).channels());
    }

    @Test
    void whileTheHubIsDownTheLastSiteItNamedIsUsed() throws Exception {
        HubSitesTest.Stub hub = new HubSitesTest.Stub().site(GUILD, SITE);
        new SiteChannelMappings(db, HubSites.on(hub)).siteFor(GUILD);

        SiteChannelMappings down = new SiteChannelMappings(db, HubSites.on(new HubSitesTest.Stub()));
        assertEquals(Optional.of(SITE), down.siteFor(GUILD));
        assertTrue(down.replace(GUILD, SiteChannelMappings.Kind.NEWS, List.of()));
        assertEquals(1, ownNewsRows(), "still never the bot's own table");
    }

    @Test
    void switchOffOrNoHubSiteKeepsTheBotsOwnTable() throws Exception {
        SiteChannelMappings off = new SiteChannelMappings(db, HubSites.off());
        assertEquals(Optional.empty(), off.channels(GUILD, SiteChannelMappings.Kind.NEWS));
        assertEquals(List.of(new SiteNewsService.NewsChannel(NEWS, "News", 0)), new SiteNewsService(db, off).channels(GUILD));

        HubSitesTest.Stub noSite = new HubSitesTest.Stub().answer("/v1/guilds/" + GUILD + "/site", 404, null);
        SiteChannelMappings none = new SiteChannelMappings(db, HubSites.on(noSite));
        SiteNewsService news = new SiteNewsService(db, none);
        news.replaceChannels(GUILD, List.of(new SiteNewsService.NewsChannel(PICKED, null, 0)));
        assertEquals(PICKED, new SiteNewsService(db, off).channels(GUILD).get(0).channelId(), "a server with no hub site writes its own table");
    }
}
