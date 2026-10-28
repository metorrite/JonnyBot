package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.runescape.RsInteractionListener;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.internal.CommunitySettings.SiteMemberOptions;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.member.MemberProfileRepository.Profile;
import com.runeclans.bot.permission.MemberAccess;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A clan site's "me" actions (T-369) go through {@link MemberApi#handle(HttpExchange, Guild, String, boolean)} with that site's server: only
 * for someone in that server, and every read and write is for that server, never the home one. Fake ids are built at run time.
 */
class MemberApiClanSiteTest {
    private static final long CLAN_GUILD = Long.parseLong("5".repeat(17) + "1");
    private static final long USER = Long.parseLong("4".repeat(17) + "1");
    private static final long STRANGER = Long.parseLong("4".repeat(17) + "2");

    private MemberProfileRepository profiles;
    private PlayerLinkRepository links;
    private MemberEventsApi events;
    private CommunitySettings community;
    private MemberApi api;
    private com.runeclans.bot.discord.jobs.DiscordOutbox outbox;
    private Guild guild;
    private Member member;

    @BeforeEach
    void setUp() {
        profiles = mock(MemberProfileRepository.class);
        links = mock(PlayerLinkRepository.class);
        events = mock(MemberEventsApi.class);
        community = mock(CommunitySettings.class);
        outbox = mock(com.runeclans.bot.discord.jobs.DiscordOutbox.class);
        api = new MemberApi(profiles, links, mock(PollService.class), mock(SignupService.class), mock(MemberAccess.class), mock(Debouncer.class), new SiteCache(), community,
                mock(SiteStatsRepository.class), mock(RsInteractionListener.class), events, mock(HighlightMemberApi.class), mock(ChatLevelMemberApi.class), outbox);

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(CLAN_GUILD);
        member = mock(Member.class);
        User user = mock(User.class);
        when(user.getName()).thenReturn("someone");
        when(member.getUser()).thenReturn(user);
        when(member.getId()).thenReturn(Long.toString(USER));
        when(member.getEffectiveName()).thenReturn("Someone");
        when(member.getRoles()).thenReturn(List.of());
        when(guild.getMemberById(USER)).thenReturn(member);
        @SuppressWarnings("unchecked")
        CacheRestAction<Member> missing = mock(CacheRestAction.class);
        when(missing.complete()).thenThrow(new RuntimeException("Unknown Member"));
        when(guild.retrieveMemberById(STRANGER)).thenReturn(missing);

        when(profiles.getProfile(anyLong(), anyLong())).thenAnswer(i -> Profile.defaults(i.getArgument(0), i.getArgument(1)));
        when(profiles.goalsFor(anyLong(), anyLong())).thenReturn(List.of());
        when(links.getLinksForUser(anyLong(), anyLong())).thenReturn(List.of());
        when(community.siteMemberOptions(anyLong())).thenReturn(new SiteMemberOptions(false, false));
    }

    record Reply(int status, DataObject body) {}

    private Reply call(String method, String route, String query, String body, boolean clanSite) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = {-1};
        String path = clanSite ? "/internal/directory/sites/clan/me/" + route : "/internal/me/" + route;
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost" + path + (query == null ? "" : "?" + query)));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        if (clanSite) api.handle(exchange, guild, route, true);
        else api.handle(exchange, guild);
        String text = out.toString(StandardCharsets.UTF_8);
        return new Reply(status[0], text.isBlank() ? DataObject.empty() : DataObject.fromJson(text));
    }

    @Test
    void someoneWhoIsNotInTheServerIsRefusedBeforeAnythingIsReadOrWritten() throws Exception {
        for (String[] action : List.of(new String[] {"GET", "settings"}, new String[] {"PUT", "settings"}, new String[] {"GET", "goals"}, new String[] {"POST", "goals"},
                new String[] {"GET", "link"}, new String[] {"POST", "polls/vote"}, new String[] {"POST", "signups/join"}, new String[] {"POST", "events/join"},
                new String[] {"GET", "coffer"}, new String[] {"GET", "server-profile"}, new String[] {"POST", "nickname"})) {
            boolean read = action[0].equals("GET");
            Reply r = call(action[0], action[1], read ? "userId=" + STRANGER : null, read ? null : "{\"userId\":\"" + STRANGER + "\"}", true);
            assertEquals(403, r.status(), action[0] + " " + action[1]);
            assertEquals(MemberApi.NOT_IN_SERVER, r.body().getString("error"));
        }
        verifyNoInteractions(profiles, links, events);
    }

    @Test
    void whenDiscordHangsTheMemberCheckAnswers503NotAWrongNo() throws Exception {
        com.runeclans.bot.discord.DiscordLookups.forgetAll();
        try {
            @SuppressWarnings("unchecked")
            CacheRestAction<Member> hanging = mock(CacheRestAction.class);
            when(hanging.submit()).thenReturn(new java.util.concurrent.CompletableFuture<>());
            when(guild.retrieveMemberById(STRANGER)).thenReturn(hanging);

            long started = System.nanoTime();
            Reply r = call("GET", "server-profile", "userId=" + STRANGER, null, true);
            long tookMs = (System.nanoTime() - started) / 1_000_000;

            assertEquals(503, r.status());
            assertEquals("DISCORD_SLOW", r.body().getString("code"));
            org.junit.jupiter.api.Assertions.assertTrue(tookMs < 3_000, "took " + tookMs + " ms");
        } finally {
            com.runeclans.bot.discord.DiscordLookups.forgetAll();
        }
    }

    @Test
    void settingsAreReadAndSavedForTheClanSitesServer() throws Exception {
        assertEquals(200, call("GET", "settings", "userId=" + USER, null, true).status());
        verify(profiles).getProfile(CLAN_GUILD, USER);

        Reply saved = call("PUT", "settings", null, "{\"userId\":\"" + USER + "\",\"bio\":\"Hi\",\"hideFromLeaderboards\":true}", true);
        assertEquals(200, saved.status());
        ArgumentCaptor<Profile> written = ArgumentCaptor.forClass(Profile.class);
        verify(profiles).saveProfile(written.capture());
        assertEquals(CLAN_GUILD, written.getValue().guildId(), "the row belongs to the clan site's server");
        assertEquals(USER, written.getValue().userId());
        assertTrue(written.getValue().hideFromLeaderboards());
    }

    @Test
    void hideRecapIsReadAndSavedWithoutChoosingThePrivacyToggles() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Profile> stored = new java.util.concurrent.atomic.AtomicReference<>(Profile.defaults(CLAN_GUILD, USER));
        when(profiles.getProfile(CLAN_GUILD, USER)).thenAnswer(i -> stored.get());
        doAnswer(i -> {
            stored.set(i.getArgument(0));
            return null;
        }).when(profiles).saveProfile(any());

        assertFalse(call("GET", "settings", "userId=" + USER, null, true).body().getBoolean("hideRecap"), "recaps are shown unless the member says no");

        Reply saved = call("PUT", "settings", null, "{\"userId\":\"" + USER + "\",\"hideRecap\":true}", true);
        assertEquals(200, saved.status());
        assertTrue(saved.body().getBoolean("hideRecap"));
        assertFalse(stored.get().privacyChosen(), "not one of the privacy toggles: the server's defaults still apply to those");
        assertFalse(saved.body().getBoolean("privacyChosen"));
        assertTrue(call("GET", "settings", "userId=" + USER, null, true).body().getBoolean("hideRecap"));

        call("PUT", "settings", null, "{\"userId\":\"" + USER + "\",\"bio\":\"Hi\"}", true);
        assertTrue(stored.get().hideRecap(), "a save that leaves it out keeps it");
        call("PUT", "settings", null, "{\"userId\":\"" + USER + "\",\"hideRecap\":false}", true);
        assertFalse(stored.get().hideRecap(), "and it can be turned back on for the clan");
    }

    @Test
    void goalsAndEventsUseTheClanSitesServer() throws Exception {
        assertEquals(200, call("GET", "goals", "userId=" + USER, null, true).status());
        verify(profiles).goalsFor(CLAN_GUILD, USER);
        when(events.mine(guild, USER)).thenReturn(DataObject.empty().put("events", 0));
        assertEquals(200, call("GET", "events", "userId=" + USER, null, true).status());
        verify(events).mine(guild, USER);
    }

    @Test
    void nicknameAndColourChangesWaitForTheServerToAllowThem() throws Exception {
        assertEquals(403, call("POST", "nickname", null, "{\"userId\":\"" + USER + "\",\"nickname\":\"New\"}", true).status());
        assertEquals(403, call("POST", "color-role", null, "{\"userId\":\"" + USER + "\",\"color\":null}", true).status());
        verify(member, never()).modifyNickname(any());

        Reply profile = call("GET", "server-profile", "userId=" + USER, null, true);
        assertEquals(200, profile.status());
        assertEquals("Someone", profile.body().getString("displayName"));
        assertFalse(profile.body().getBoolean("canChangeNickname"));

        when(community.siteMemberOptions(CLAN_GUILD)).thenReturn(new SiteMemberOptions(true, false));
        // T-456: the change is queued as a Discord job and the answer comes at once; the request never talks to Discord
        when(outbox.enqueue(eq("member.nickname"), eq(CLAN_GUILD), anyLong(), any(), any())).thenReturn(new com.runeclans.bot.discord.jobs.DiscordJob(77, "member.nickname",
                CLAN_GUILD, USER, null, net.dv8tion.jda.api.utils.data.DataObject.empty(), com.runeclans.bot.discord.jobs.DiscordJob.State.QUEUED, 0, 0, null, null, null, null, null));
        Reply renamed = call("POST", "nickname", null, "{\"userId\":\"" + USER + "\",\"nickname\":\"New\"}", true);
        assertEquals(200, renamed.status());
        assertEquals("New", renamed.body().getString("nickname"));
        assertEquals("77", renamed.body().getObject("job").getString("id"));
        assertEquals("queued", renamed.body().getObject("job").getString("state"));
        verify(member, never()).modifyNickname(any());
        assertEquals(400, call("POST", "nickname", null, "{\"userId\":\"" + USER + "\",\"nickname\":\"" + "x".repeat(33) + "\"}", true).status());
    }

    @Test
    void theHomeSiteIsUnchanged() throws Exception {
        // no membership check on the home site's own routes (as before), and the server-profile routes stay on its older endpoints
        assertEquals(200, call("GET", "settings", "userId=" + STRANGER, null, false).status());
        assertEquals(405, call("GET", "server-profile", "userId=" + USER, null, false).status());
        assertEquals(405, call("POST", "nickname", null, "{\"userId\":\"" + USER + "\",\"nickname\":\"New\"}", false).status());
    }
}
