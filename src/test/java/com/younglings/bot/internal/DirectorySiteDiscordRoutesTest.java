package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The clan site's Discord routes (T-365) behind the real directory: only for a published site, off means 404 "disabled", and a per-site limit. */
class DirectorySiteDiscordRoutesTest {
    private static final long GUILD = 7L;
    private static final String USER = "5".repeat(18);

    private final ClanProfileRepository repository = mock(ClanProfileRepository.class);
    private final ClanProfileService service = mock(ClanProfileService.class);
    private final ClanSiteService sites = mock(ClanSiteService.class);
    private final SiteDiscordService discord = mock(SiteDiscordService.class);
    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);
    private final com.younglings.bot.clansite.BotlessSiteRepository botless = mock(com.younglings.bot.clansite.BotlessSiteRepository.class);
    /** The real tenant routing (a bot-less site's Discord-only routes answer before anything else is touched). */
    private final SiteApi siteApi = mock(SiteApi.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final ClanProfile clan = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "younglings", "Younglings", null, null, null, null, null, Set.of(),
            ClanProfile.Recruiting.CLOSED, null, null, false, null, null, null, null, null, null);
    private DirectoryApi api;

    @BeforeEach
    void setUp() {
        api = new DirectoryApi(repository, service, mock(SiteStatsRepository.class), List.of(
                new DirectoryClanSiteRoutes(sites, siteApi, repository, mock(com.younglings.bot.clansite.ClanSiteLayoutRepository.class),
                        mock(com.younglings.bot.clansite.ClanSitePluginFeed.class)),
                new DirectorySiteDiscordRoutes(sites, discord)), botless);
        when(repository.bySlug("younglings")).thenReturn(Optional.of(clan));
        when(service.publicView(GUILD, true)).thenReturn(Optional.of(clan));
        when(service.publicView(eq(clan), any(), eq(true))).thenReturn(Optional.of(clan));
        ClanSite site = mock(ClanSite.class);
        when(site.guildId()).thenReturn(GUILD);
        when(sites.visible(GUILD)).thenReturn(Optional.of(site));
        when(jda.getGuildById(GUILD)).thenReturn(guild);
    }

    private record Reply(int status, DataObject body) {}

    private Reply call(String method, String pathAndQuery, String body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost" + pathAndQuery));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        org.mockito.Mockito.doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        api.handle(exchange, jda);
        return new Reply(status[0], DataObject.fromJson(out.toString(StandardCharsets.UTF_8)));
    }

    @Test
    void offIsDisabledAndAModeAnswers() throws Exception {
        Reply off = call("GET", "/internal/directory/sites/younglings/data/discord/online", null);
        assertEquals(404, off.status());
        assertEquals("disabled", off.body().getString("error"));

        when(discord.online(guild)).thenReturn(DataObject.empty().put("total", 3));
        assertEquals(3, call("GET", "/internal/directory/sites/younglings/data/discord/online", null).body().getInt("total"));
        assertEquals(405, call("POST", "/internal/directory/sites/younglings/data/discord/online", "{}").status());
    }

    @Test
    void theFeedPassesTheChannelAndLimit() throws Exception {
        when(discord.feed(guild, "12", 5)).thenReturn(DataObject.empty().put("posts", "x"));
        assertEquals("x", call("GET", "/internal/directory/sites/younglings/data/discord/feed?channel=12&limit=5", null).body().getString("posts"));
        assertEquals(404, call("GET", "/internal/directory/sites/younglings/data/discord/feed?channel=99", null).status());
    }

    @Test
    void anUnpublishedSiteHasNoDiscordData() throws Exception {
        when(sites.visible(GUILD)).thenReturn(Optional.empty());
        assertEquals(404, call("GET", "/internal/directory/sites/younglings/data/discord/online", null).status());
        assertEquals(404, call("GET", "/internal/directory/sites/nobody/data/discord/feed", null).status());
        verify(discord, never()).online(any());
    }

    @Test
    void aMemberReadsAndSavesTheirOwnChoice() throws Exception {
        when(discord.memberChoice(guild, Long.parseLong(USER))).thenReturn(DataObject.empty().put("hidden", false));
        when(discord.saveMemberChoice(guild, Long.parseLong(USER), true)).thenReturn(DataObject.empty().put("hidden", true));

        assertEquals(false, call("GET", "/internal/directory/sites/younglings/discord/me?userId=" + USER, null).body().getBoolean("hidden"));
        assertTrue(call("POST", "/internal/directory/sites/younglings/discord/me?userId=" + USER, "{\"hidden\":true}").body().getBoolean("hidden"));
        assertEquals(400, call("GET", "/internal/directory/sites/younglings/discord/me", null).status());
        assertEquals(400, call("GET", "/internal/directory/sites/younglings/discord/me?userId=abc", null).status());
    }

    @Test
    void eachSiteIsRateLimited() throws Exception {
        when(discord.online(guild)).thenReturn(DataObject.empty().put("total", 1));
        int limited = 0;
        for (int i = 0; i < 40; i++) {
            if (call("GET", "/internal/directory/sites/younglings/data/discord/online", null).status() == 429) limited++;
        }
        assertTrue(limited > 0, "a burst past 20 is refused");
    }

    @Test
    void otherSiteRoutesStillReachTheirModule() throws Exception {
        assertEquals(404, call("GET", "/internal/directory/sites/younglings/data/discord/other", null).status());
        verify(discord, never()).feed(any(), any(), anyInt());
    }

    /** The website (runeclans A1) shows its "Needs RuneClan Bot" placeholder for any {"needsBot": true} answer from these routes. */
    @Test
    void aBotlessSitesDiscordDataPollsSignupsAndEventsSayTheyNeedTheBot() throws Exception {
        when(jda.getGuildById(GUILD)).thenReturn(null);
        when(sites.botless(GUILD)).thenReturn(true);
        when(botless.contains(GUILD)).thenReturn(true);
        for (String path : new String[]{"sites/younglings/data/discord/online", "sites/younglings/data/discord/feed", "sites/younglings/data/discord/events",
                "sites/younglings/data/polls", "sites/younglings/data/signups", "clans/younglings/events"}) {
            Reply reply = call("GET", "/internal/directory/" + path, null);
            assertEquals(200, reply.status(), path);
            assertTrue(reply.body().getBoolean("needsBot"), path);
        }
        assertEquals(0, call("GET", "/internal/directory/clans/younglings/events", null).body().getArray("events").length());
        verify(discord, never()).online(any());
    }

    @Test
    void aClanWhoseServerTheBotLeftButIsNotBotlessHasPlainEmptyEvents() throws Exception {
        when(jda.getGuildById(GUILD)).thenReturn(null);
        Reply reply = call("GET", "/internal/directory/clans/younglings/events", null);
        assertEquals(200, reply.status());
        assertFalse(reply.body().hasKey("needsBot"));
        assertEquals(404, call("GET", "/internal/directory/sites/younglings/data/discord/other", null).status());
    }
}
