package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clansite.BotlessSiteRepository;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.clansite.ClanSitePluginFeed;
import com.younglings.bot.clansite.ClanSiteService;
import net.dv8tion.jda.api.JDA;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-586: {@code sites/{slug}/data/in-game} reaches its own module, next to the clan site routes, and only for a published site. */
class DirectoryInGameRoutesTest {
    private static final long GUILD = 7L;

    private final ClanProfileRepository repository = mock(ClanProfileRepository.class);
    private final ClanProfileService service = mock(ClanProfileService.class);
    private final ClanSiteService sites = mock(ClanSiteService.class);
    private final SiteInGameNow inGame = mock(SiteInGameNow.class);
    private final JDA jda = mock(JDA.class);
    private final ClanProfile clan = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "younglings", "Younglings", null, null, null, null, null, Set.of(),
            ClanProfile.Recruiting.CLOSED, null, null, false, null, null, null, null, null, null);
    private DirectoryApi api;

    @BeforeEach
    void setUp() {
        // the clan site routes come first, as they may in the bot: they must leave data/in-game alone
        api = new DirectoryApi(repository, service, mock(SiteStatsRepository.class), List.of(
                new DirectoryClanSiteRoutes(sites, mock(SiteApi.class), repository, mock(ClanSiteLayoutRepository.class), mock(ClanSitePluginFeed.class)),
                new DirectoryInGameRoutes(sites, inGame)), mock(BotlessSiteRepository.class), mock(SiteDiscordService.class));
        when(repository.bySlug("younglings")).thenReturn(Optional.of(clan));
        when(service.publicView(GUILD, true)).thenReturn(Optional.of(clan));
        when(service.publicView(eq(clan), any(), eq(true))).thenReturn(Optional.of(clan));
    }

    private record Reply(int status, DataObject body) {}

    private Reply call(String method, String path) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost" + path));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
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
    void answersForAPublishedSite() throws Exception {
        when(sites.visible(GUILD)).thenReturn(Optional.of(new ClanSite(GUILD, true, true, ClanSite.Template.values()[0], "{}", "[]", null, null, null, null)));
        when(inGame.answer(eq(GUILD), isNull())).thenReturn(InGameNowRules.answer("members", List.of("Skill Queen"), 0));
        Reply r = call("GET", "/internal/directory/sites/younglings/data/in-game");
        assertEquals(200, r.status());
        assertEquals("Skill Queen", r.body().getArray("members").getObject(0).getString("rsn"));
        assertEquals(405, call("POST", "/internal/directory/sites/younglings/data/in-game").status());
    }

    @Test
    void anUnpublishedSiteIsNotFound() throws Exception {
        when(sites.visible(GUILD)).thenReturn(Optional.empty());
        assertEquals(404, call("GET", "/internal/directory/sites/younglings/data/in-game").status());
    }
}
