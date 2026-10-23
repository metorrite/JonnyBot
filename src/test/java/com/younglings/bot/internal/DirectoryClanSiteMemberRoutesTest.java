package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code sites/{slug}/me/...} (T-369): reaches {@link MemberApi} with the site's own server, only for a published site. Fake ids built at run time. */
class DirectoryClanSiteMemberRoutesTest {
    private static final long CLAN_GUILD = Long.parseLong("3".repeat(17) + "1");
    private static final long USER = Long.parseLong("2".repeat(17) + "1");

    private ClanSiteService sites;
    private MemberApi memberApi;
    private SiteApi siteApi;
    private JDA jda;
    private Guild guild;
    private DirectoryClanSiteMemberRoutes routes;

    @BeforeEach
    void setUp() {
        sites = mock(ClanSiteService.class);
        memberApi = mock(MemberApi.class);
        siteApi = mock(SiteApi.class);
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(CLAN_GUILD)).thenReturn(guild);
        routes = new DirectoryClanSiteMemberRoutes(sites, memberApi, siteApi);
    }

    private void published() {
        when(sites.visible(CLAN_GUILD)).thenReturn(Optional.of(new ClanSite(CLAN_GUILD, true, true, ClanSite.Template.values()[0], "{}", "[]", null, null, null, null)));
    }

    record Outcome(boolean handled, int status, HttpExchange exchange) {}

    private Outcome call(String method, String pathAndQuery) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/internal/directory/" + pathAndQuery));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        ClanProfile profile = mock(ClanProfile.class);
        when(profile.guildId()).thenReturn(CLAN_GUILD);
        String[] parts = pathAndQuery.split("\\?")[0].split("/");
        DirectoryRequest request = new DirectoryRequest(exchange, jda, method, parts, slug -> slug.equals("clan") ? Optional.of(profile) : Optional.empty(), p -> DataObject.empty());
        boolean handled = routes.route(request);
        return new Outcome(handled, status[0], exchange);
    }

    @Test
    void aPublishedSitesMeActionGoesToMemberApiWithThatServer() throws Exception {
        published();
        Outcome vote = call("POST", "sites/clan/me/polls/vote");
        assertTrue(vote.handled());
        verify(memberApi).handle(vote.exchange(), guild, "polls/vote", true);
        Outcome settings = call("GET", "sites/clan/me/settings?userId=" + USER);
        verify(memberApi).handle(settings.exchange(), guild, "settings", true);
    }

    @Test
    void anUnpublishedOrUnknownSiteIsNotFound() throws Exception {
        assertEquals(404, call("GET", "sites/clan/me/settings?userId=" + USER).status(), "not published");
        published();
        assertEquals(404, call("GET", "sites/nobody/me/settings?userId=" + USER).status());
        verify(memberApi, never()).handle(any(), any(), anyString(), anyBoolean());
    }

    @Test
    void otherShapesAreLeftToTheOtherModules() throws Exception {
        assertFalse(call("GET", "sites/clan/data/members").handled());
        assertFalse(call("GET", "sites/clan/viewer?userId=" + USER).handled());
        assertFalse(call("GET", "sites/clan").handled());
    }

    @Test
    void noticesAreOnlyForSomeoneInTheServer() throws Exception {
        published();
        @SuppressWarnings("unchecked")
        CacheRestAction<Member> missing = mock(CacheRestAction.class);
        when(missing.complete()).thenThrow(new RuntimeException("Unknown Member"));
        when(guild.retrieveMemberById(USER)).thenReturn(missing);
        assertEquals(403, call("GET", "sites/clan/me/notices?userId=" + USER).status());
        verify(siteApi, never()).me(any(), anyString());

        when(guild.getMemberById(USER)).thenReturn(mock(Member.class));
        when(siteApi.me(eq(guild), eq(Long.toString(USER)))).thenReturn(DataObject.empty().put("state", "NONE"));
        assertEquals(200, call("GET", "sites/clan/me/notices?userId=" + USER).status());
        assertEquals(400, call("GET", "sites/clan/me/notices?userId=abc").status());
        assertEquals(405, call("POST", "sites/clan/me/notices").status());
    }
}
