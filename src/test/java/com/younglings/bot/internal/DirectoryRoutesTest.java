package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.ClanRecruitingRepository;
import com.younglings.bot.clanprofile.JoinLinks;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.forms.WebFormRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every directory route still answers after the routes moved out of {@link DirectoryApi} into {@link DirectoryRouteModule}s (T-133). */
class DirectoryRoutesTest {
    private static final long GUILD = 7L;

    private final ClanProfileRepository repository = mock(ClanProfileRepository.class);
    private final ClanProfileService service = mock(ClanProfileService.class);
    private final AllianceRepository alliances = mock(AllianceRepository.class);
    private final SiteSwitchAdminApi siteSwitches = mock(SiteSwitchAdminApi.class);
    private final HubEventsApi hubEvents = mock(HubEventsApi.class);
    private final ClanSiteService sites = mock(ClanSiteService.class);
    private final SiteApi siteApi = mock(SiteApi.class);
    private final JDA jda = mock(JDA.class);
    private final ClanProfile clan = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "younglings", "Younglings", null, null, null, null, null, Set.of(),
            ClanProfile.Recruiting.CLOSED, null, null, false, null, null, null, null, null, null);

    /** A section a feature adds to the clan page, to see it on clans/{slug} and inside sites/{slug}. */
    private final DirectoryRouteModule section = new DirectoryRouteModule() {
        @Override
        public void addToClan(ClanProfile profile, DataObject json) {
            json.put("testSection", profile.slug());
        }
    };

    private DirectoryApi api;

    @BeforeEach
    void setUp() {
        api = new DirectoryApi(repository, service, mock(SiteStatsRepository.class), List.of(
                new DirectoryAllianceRoutes(alliances, repository, service),
                new DirectorySiteSwitchRoutes(siteSwitches),
                new DirectoryHubEventRoutes(hubEvents),
                new DirectoryClanSiteRoutes(sites, siteApi, repository, mock(com.younglings.bot.clansite.ClanSiteLayoutRepository.class),
                        mock(com.younglings.bot.clansite.ClanSitePluginFeed.class)),
                new DirectorySlugRoutes(repository),
                section));
        when(repository.bySlug("younglings")).thenReturn(Optional.of(clan));
        when(service.publicView(GUILD, true)).thenReturn(Optional.of(clan));
        when(service.publicView(eq(clan), any(), eq(true))).thenReturn(Optional.of(clan));
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
    void siteSwitchesComeFromTheirModule() throws Exception {
        when(siteSwitches.states()).thenReturn(DataObject.empty().put("hub", true));
        Reply r = call("GET", "/internal/directory/site-switches", null);
        assertEquals(200, r.status());
        assertTrue(r.body().getBoolean("hub"));
        assertEquals(405, call("POST", "/internal/directory/site-switches", "{}").status());
    }

    @Test
    void hubEventsListOneJoinAndReport() throws Exception {
        when(hubEvents.list(jda, "u1")).thenReturn(DataObject.empty().put("events", "list"));
        when(hubEvents.one(jda, "42", null)).thenReturn(DataObject.empty().put("event", "one"));
        when(hubEvents.join(jda, "42", "99", null)).thenReturn(new HubEventsApi.Answer(200, DataObject.empty().put("joined", true)));
        when(hubEvents.join(jda, "42", "99", "cohost-1")).thenReturn(new HubEventsApi.Answer(200, DataObject.empty().put("joined", "via")));
        when(hubEvents.leave(jda, "42", "99")).thenReturn(new HubEventsApi.Answer(200, DataObject.empty().put("left", true)));
        when(hubEvents.mine(jda, "u1")).thenReturn(DataObject.empty().put("events", "mine"));
        when(hubEvents.report(eq(jda), eq("42"), any())).thenReturn(new HubEventsApi.Answer(202, DataObject.empty().put("ok", true)));

        assertEquals("list", call("GET", "/internal/directory/hub-events?viewer=u1", null).body().getString("events"));
        assertEquals("one", call("GET", "/internal/directory/hub-events/42", null).body().getString("event"));
        assertEquals(200, call("POST", "/internal/directory/hub-events/42/join", "{\"userId\":\"99\"}").status());
        assertEquals("via", call("POST", "/internal/directory/hub-events/42/join", "{\"userId\":\"99\",\"via\":\"cohost-1\"}").body().getString("joined"));
        assertTrue(call("POST", "/internal/directory/hub-events/42/leave", "{\"userId\":\"99\"}").body().getBoolean("left"));
        assertEquals(405, call("GET", "/internal/directory/hub-events/42/leave", null).status());
        assertEquals("mine", call("GET", "/internal/directory/hub-events/mine?viewer=u1", null).body().getString("events"));
        assertEquals(202, call("POST", "/internal/directory/hub-events/42/report", "{\"reason\":\"spam spam spam\"}").status());

        assertEquals(405, call("GET", "/internal/directory/hub-events/42/join", null).status());
        assertEquals(405, call("POST", "/internal/directory/hub-events/42", "{}").status());
        Reply unreadable = call("POST", "/internal/directory/hub-events/42/report", "not json");
        assertEquals(400, unreadable.status());
        assertEquals("That report couldn't be read.", unreadable.body().getString("error"));
        assertEquals("No such event", call("GET", "/internal/directory/hub-events/42/other", null).body().getString("error"));
    }

    @Test
    void alliancePagesNeedANumericId() throws Exception {
        when(alliances.alliance(5L)).thenReturn(Optional.empty());
        Reply missing = call("GET", "/internal/directory/alliances/5", null);
        assertEquals(404, missing.status());
        assertEquals("No such alliance", missing.body().getString("error"));
        verify(alliances).alliance(5L);
        assertEquals("Not found", call("GET", "/internal/directory/alliances/abc", null).body().getString("error"));
        assertEquals(405, call("POST", "/internal/directory/alliances/5", "{}").status());
    }

    @Test
    void clanSitesAndTheirDataCarryTheClanWithEverySection() throws Exception {
        ClanSite site = new ClanSite(GUILD, true, true, ClanSite.Template.values()[0], "{}", "[]", null, null, null, null);
        Guild guild = mock(Guild.class);
        when(sites.visible(GUILD)).thenReturn(Optional.of(site));
        when(sites.blocks(GUILD)).thenReturn(List.of());
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(siteApi.tenant(eq(guild), eq("members"), any())).thenReturn(DataObject.empty().put("members", 3));

        Reply page = call("GET", "/internal/directory/sites/younglings", null);
        assertEquals(200, page.status());
        assertEquals("younglings", page.body().getObject("clan").getString("testSection"));

        Reply data = call("GET", "/internal/directory/sites/younglings/data/members", null);
        assertEquals(200, data.status());
        assertEquals(3, data.body().getInt("members"));

        assertEquals("No such site", call("GET", "/internal/directory/sites/nobody", null).body().getString("error"));
        assertEquals("Not found", call("GET", "/internal/directory/sites/younglings/other", null).body().getString("error"));
    }

    @Test
    void aClanPageHasEveryModulesSection() throws Exception {
        when(alliances.visibility(anyLong())).thenReturn(com.younglings.bot.alliance.AllianceVisibility.Visibility.HIDDEN);
        Reply r = call("GET", "/internal/directory/clans/younglings", null);
        assertEquals(200, r.status());
        assertEquals("younglings", r.body().getString("testSection"));
        assertTrue(r.body().hasKey("alliances"), "the alliance module's section (null when hidden) is on the page");
    }

    @Test
    void aGivenUpAddressForwardsToTheClansNewOneDuringTheGracePeriod() throws Exception {
        ClanSite site = new ClanSite(GUILD, true, true, ClanSite.Template.values()[0], "{}", "[]", null, null, null, null);
        when(sites.visible(GUILD)).thenReturn(Optional.of(site));
        when(repository.bySlug("old-name")).thenReturn(Optional.empty());
        when(repository.slugHolder("old-name")).thenReturn(Optional.of(GUILD));
        when(repository.get(GUILD)).thenReturn(Optional.of(clan));
        Reply moved = call("GET", "/internal/directory/sites/old-name", null);
        assertEquals(200, moved.status());
        assertEquals("younglings", moved.body().getString("movedTo"));
        // nobody held it: an ordinary unknown address
        when(repository.slugHolder("never-used")).thenReturn(Optional.empty());
        assertEquals(404, call("GET", "/internal/directory/sites/never-used", null).status());
        // the clan's site is no longer published: nothing to forward to
        when(sites.visible(GUILD)).thenReturn(Optional.empty());
        assertEquals(404, call("GET", "/internal/directory/sites/old-name", null).status());
    }

    @Test
    void slugCheckSaysWhetherAnAddressCanBeHad() throws Exception {
        when(repository.slugTaken("green-angels", -1)).thenReturn(false);
        when(repository.slugTaken("younglings", -1)).thenReturn(true);
        Reply free = call("GET", "/internal/directory/slug-check?slug=Green-Angels", null);
        assertEquals(200, free.status());
        assertEquals("green-angels", free.body().getString("slug"));
        assertTrue(free.body().getBoolean("available"));
        assertTrue(free.body().isNull("problem"));
        Reply taken = call("GET", "/internal/directory/slug-check?slug=younglings", null);
        assertEquals(false, taken.body().getBoolean("available"));
        assertTrue(taken.body().getString("problem").contains("Another clan"));
        Reply reserved = call("GET", "/internal/directory/slug-check?slug=status", null);
        assertEquals(false, reserved.body().getBoolean("available"));
        assertTrue(reserved.body().getString("problem").contains("reserved"));
        assertEquals(false, call("GET", "/internal/directory/slug-check?slug=x", null).body().getBoolean("available"));
        assertEquals(405, call("POST", "/internal/directory/slug-check?slug=abc", "{}").status());
    }

    @Test
    void unknownPathsAreNotFound() throws Exception {
        assertEquals("Not found", call("GET", "/internal/directory/nothing-here", null).body().getString("error"));
        assertEquals(404, call("GET", "/internal/elsewhere", null).status());
    }

    @Test
    void recruitingAddsItsPostToTheClanPage() {
        ClanRecruitingRepository recruiting = mock(ClanRecruitingRepository.class);
        when(recruiting.of(GUILD)).thenReturn(new com.younglings.bot.clanprofile.ClanRecruiting(GUILD, "We raid on Fridays", null, null, null));
        DataObject json = DataObject.empty();
        new DirectoryRecruitingSection(recruiting, mock(WebFormRepository.class), new JoinLinks(mock(com.younglings.bot.configure.GuildSettingsService.class, org.mockito.Answers.RETURNS_DEEP_STUBS), recruiting)).addToClan(clan, json);
        assertEquals("We raid on Fridays", json.getObject("recruitingPost").getString("post"));
        assertTrue(json.getObject("recruitingPost").isNull("applyFormPath"), "a clan that is not recruiting shows no form");
        verify(recruiting).of(GUILD);
    }
}
