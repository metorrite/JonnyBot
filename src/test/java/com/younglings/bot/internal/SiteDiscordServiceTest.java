package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteConnectionStore;
import com.younglings.bot.clansite.SiteConnectionStore.Channel;
import com.younglings.bot.clansite.SiteConnectionStore.Connection;
import com.younglings.bot.clansite.SiteDiscordSettings;
import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.younglings.bot.config.IntentSwitches;
import com.younglings.bot.hubsites.HubSites;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.member.MemberProfileRepository;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.RoleColors;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Discord on a clan website over a mock server (T-365, N7): off by default, never more than the site's connection settings allow, only public
 * channels, nothing at all while the server is disconnected, and the Connections page's saves.
 */
class SiteDiscordServiceTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long PUBLIC = Long.parseLong("1".repeat(18));
    private static final long PRIVATE = Long.parseLong("2".repeat(18));
    private static final long ADULT = Long.parseLong("3".repeat(18));

    private final SiteConnectionStore connections = mock(SiteConnectionStore.class);
    private final SiteDiscordSettings settings = mock(SiteDiscordSettings.class);
    private final SiteNewsService news = mock(SiteNewsService.class);
    private final MemberProfileRepository profiles = mock(MemberProfileRepository.class);
    private SiteDiscordService service;
    private Guild guild;
    private Role everyone;
    private SelfMember self;
    private TextChannel newsChannel;
    private TextChannel staffChannel;

    @BeforeEach
    void setUp() {
        IntentSwitches.allOn();
        service = new SiteDiscordService(connections, settings, news, profiles, new SiteCache(), HubSites.off());
        guild = mock(Guild.class);
        everyone = mock(Role.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getPublicRole()).thenReturn(everyone);
        when(guild.getSelfMember()).thenReturn(self);
        newsChannel = channel(PUBLIC, "news", false, true);
        staffChannel = channel(PRIVATE, "staff", false, false);
        channel(ADULT, "memes", true, true);
        site(OnlineMode.OFF);
    }

    /** The site's connections: its own server, connected, with this mode, 24 names grouped, 5 posts, events on, and these channels. */
    private Connection site(OnlineMode mode, long... channelIds) {
        return site(GUILD, mode, 24, true, 5, channelIds);
    }

    private Connection site(Long server, OnlineMode mode, int onlineLimit, boolean groups, int feedLimit, long... channelIds) {
        List<Channel> channels = new java.util.ArrayList<>();
        for (long id : channelIds) channels.add(new Channel(id, null, channels.size()));
        Connection c = new Connection(GUILD, server, null, null, SiteConnectionStore.RUNECLAN_BOT, mode, onlineLimit, groups, feedLimit, true,
                List.copyOf(channels), false);
        when(connections.of(GUILD)).thenReturn(c);
        return c;
    }

    @AfterEach
    void restoreIntents() {
        IntentSwitches.allOn();
    }

    private TextChannel channel(long id, String name, boolean nsfw, boolean everyoneReads) {
        TextChannel channel = mock(TextChannel.class);
        when(channel.getIdLong()).thenReturn(id);
        when(channel.getName()).thenReturn(name);
        when(channel.isNSFW()).thenReturn(nsfw);
        when(everyone.hasPermission(eq(channel), eq(Permission.VIEW_CHANNEL), eq(Permission.MESSAGE_HISTORY))).thenReturn(everyoneReads);
        when(self.hasPermission(eq(channel), eq(Permission.VIEW_CHANNEL), eq(Permission.MESSAGE_HISTORY))).thenReturn(true);
        when(guild.getGuildChannelById(id)).thenReturn(channel);
        return channel;
    }

    private static Member member(long id, String name, OnlineStatus status) {
        Member m = mock(Member.class);
        User user = mock(User.class);
        RoleColors colors = mock(RoleColors.class);
        when(m.getIdLong()).thenReturn(id);
        when(m.getUser()).thenReturn(user);
        when(m.getOnlineStatus()).thenReturn(status);
        when(m.getEffectiveName()).thenReturn(name);
        when(m.getColors()).thenReturn(colors);
        when(m.getEffectiveAvatarUrl()).thenReturn("https://cdn.example/avatars/" + id + ".png");
        return m;
    }

    // ---------- online ----------

    @Test
    void theOnlineListIsOffUntilAnAdminPicksAMode() {
        assertNull(service.online(guild));
        verify(guild, never()).getMembers();
    }

    @Test
    void namesLeaveOutMembersWhoOptedOutOnEitherSwitch() {
        site(OnlineMode.NAMES);
        List<Member> members = List.of(member(11, "Ann", OnlineStatus.ONLINE), member(12, "Ben", OnlineStatus.ONLINE), member(13, "Cat", OnlineStatus.IDLE));
        when(guild.getMembers()).thenReturn(members);
        when(settings.hiddenMembers(GUILD)).thenReturn(Set.of(12L));
        when(profiles.hiddenDiscordUsers(GUILD)).thenReturn(Set.of(13L));

        DataObject answer = service.online(guild);

        assertEquals(3, answer.getInt("total"));
        assertEquals(1, answer.getInt("named"));
        assertTrue(answer.toString().contains("Ann"));
        assertFalse(answer.toString().contains("Ben"));
        assertFalse(answer.toString().contains("Cat"));
    }

    @Test
    void withoutThePresenceIntentTheListIsUnavailable() {
        IntentSwitches.set(false, true);
        site(OnlineMode.COUNT);

        assertTrue(service.online(guild).getBoolean("unavailable"));
    }

    // ---------- feed ----------

    private void chosen(long... ids) {
        site(OnlineMode.OFF, ids);
    }

    @Test
    void theFeedOnlyReadsChannelsThatAreStillPublicAndDropsAvatars() {
        chosen(PUBLIC, PRIVATE, ADULT); // the two bad ones were chosen before the rules, or made private later
        DataObject post = DataObject.empty().put("id", "1").put("author", "Ann").put("avatarUrl", "https://cdn.example/a.png").put("text", "hi");
        when(news.latest(eq(guild), eq(List.of(new SiteNewsService.NewsChannel(PUBLIC, null, 0))), anyInt())).thenReturn(DataArray.empty().add(post));

        DataObject feed = service.feed(guild, null);

        assertFalse(feed.getBoolean("unavailable"));
        assertEquals(1, feed.getArray("channels").length());
        assertEquals("news", feed.getArray("channels").getObject(0).getString("name"));
        DataObject shown = feed.getArray("posts").getObject(0);
        assertEquals("Ann", shown.getString("author"));
        assertFalse(shown.hasKey("avatarUrl"));
        assertTrue(post.hasKey("avatarUrl"), "the home site's cached post is left alone");
    }

    @Test
    void oneChannelMustBeAnOptedInPublicOne() {
        chosen(PUBLIC, PRIVATE);
        when(news.latest(eq(guild), any(), anyInt())).thenReturn(DataArray.empty());

        assertNull(service.feed(guild, Long.toString(PRIVATE)));
        assertNull(service.feed(guild, Long.toString(ADULT)));
        assertEquals(0, service.feed(guild, Long.toString(PUBLIC)).getArray("posts").length());
    }

    @Test
    void theFeedAsksForTheSitesPostCountAndNeverMoreThan25() {
        site(GUILD, OnlineMode.OFF, 24, true, 7, PUBLIC);
        when(news.latest(eq(guild), any(), anyInt())).thenReturn(DataArray.empty());
        assertEquals(7, service.feed(guild, null).getInt("limit"));
        verify(news).latest(eq(guild), any(), eq(7));

        site(GUILD, OnlineMode.OFF, 24, true, 500, PUBLIC);
        service.feed(guild, Long.toString(PUBLIC));
        verify(news).latest(eq(guild), any(), eq(25));
    }

    @Test
    void withSitesOnTheHubThePublicSiteFollowsTheHubsSettingsAndKeepsTheBotsChannels() {
        site(GUILD, OnlineMode.OFF, 24, true, 5, PUBLIC);
        DataObject hubSettings = DataObject.empty()
                .put("online", DataObject.empty().put("mode", "count").put("limit", 30).put("groupByRole", false))
                .put("feed", DataObject.empty().put("limit", 9))
                .put("events", DataObject.empty().put("enabled", false));
        HubSites hub = HubSites.on(path -> path.equals("/v1/guilds/" + GUILD + "/site/settings")
                ? new RuneClansHubClient.Read(new RuneClansHubClient.Answer(200, hubSettings), false, false) : null);
        service = new SiteDiscordService(connections, settings, news, profiles, new SiteCache(), hub);
        List<Member> members = List.of(member(11, "Ann", OnlineStatus.ONLINE));
        when(guild.getMembers()).thenReturn(members);
        when(news.latest(eq(guild), any(), anyInt())).thenReturn(DataArray.empty());

        assertEquals(1, service.online(guild).getInt("total"), "the hub turned the online list on (count)");
        service.feed(guild, Long.toString(PUBLIC));
        verify(news).latest(eq(guild), eq(List.of(new SiteNewsService.NewsChannel(PUBLIC, null, 0))), eq(9));
        assertFalse(service.eventsShown(GUILD));

        // the hub down: the bot's own copy (online off)
        service = new SiteDiscordService(connections, settings, news, profiles, new SiteCache(), HubSites.on(path -> null));
        assertNull(service.online(guild));
    }

    @Test
    void aDisconnectedServerShowsNothing() {
        site(null, OnlineMode.NAMES_AVATARS, 24, true, 5, PUBLIC);

        assertNull(service.online(guild));
        assertEquals(0, service.feed(guild, null).getArray("channels").length());
        assertNull(service.feed(guild, Long.toString(PUBLIC)));
        assertFalse(service.eventsShown(GUILD));
        verify(guild, never()).getMembers();
    }

    @Test
    void theOnlineListFollowsTheSitesLimitAndGrouping() {
        site(GUILD, OnlineMode.NAMES_AVATARS, 1, false, 5);
        List<Member> members = List.of(member(11, "Ann", OnlineStatus.ONLINE), member(12, "Ben", OnlineStatus.ONLINE));
        when(guild.getMembers()).thenReturn(members);
        when(settings.hiddenMembers(GUILD)).thenReturn(Set.of());

        DataObject answer = service.online(guild);

        assertEquals(2, answer.getInt("total"));
        assertEquals(1, answer.getInt("named"));
        assertFalse(answer.getBoolean("groupByRole"));
        assertEquals("https://cdn.example/avatars/11.png?size=64",
                answer.getArray("groups").getObject(0).getArray("members").getObject(0).getString("avatarUrl"), "N1: names and avatars sends avatars");
    }

    @Test
    void withoutMessageContentTheFeedIsUnavailable() {
        IntentSwitches.set(true, false);
        chosen(PUBLIC);

        DataObject feed = service.feed(guild, null);

        assertTrue(feed.getBoolean("unavailable"));
        verify(news, never()).latest(any(), any(), anyInt());
    }

    // ---------- the Connections page ----------

    private SiteCaller admin() {
        Member actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(5L);
        when(guild.getJDA()).thenReturn(null);
        return SiteCaller.of(guild, actor);
    }

    @Test
    void aPrivateOrAgeRestrictedChannelCantBeOptedIn() {
        SiteCaller c = admin();
        for (long bad : new long[]{PRIVATE, ADULT}) {
            DataObject body = DataObject.empty().put("feed", DataObject.empty()
                    .put("channels", DataArray.empty().add(DataObject.empty().put("channelId", Long.toString(bad)))));
            TicketAdminApi.ApiError error = assertThrows(TicketAdminApi.ApiError.class, () -> service.saveConnection(c, body));
            assertEquals(403, error.status);
        }
        verify(connections, never()).saveFeatures(any(), anyLong());
    }

    @Test
    void savingKeepsLeftOutPartsAndChecksTheNumbers() {
        SiteCaller c = admin();
        Connection now = site(OnlineMode.NAMES, PUBLIC);

        service.saveConnection(c, DataObject.empty().put("online", DataObject.empty().put("mode", "names+avatars").put("limit", 40)));
        verify(connections).saveFeatures(now.withFeatures(OnlineMode.NAMES_AVATARS, 40, true, 5, true, now.channels()), 5L);

        service.saveConnection(c, DataObject.empty().put("feed", DataObject.empty().put("limit", 10)
                .put("channels", DataArray.empty().add(DataObject.empty().put("channelId", Long.toString(PUBLIC)).put("label", "News")))).put("events", DataObject.empty().put("enabled", false)));
        verify(connections).saveFeatures(now.withFeatures(OnlineMode.NAMES, 24, true, 10, false, List.of(new Channel(PUBLIC, "News", 0))), 5L);

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> service.saveConnection(c, DataObject.empty().put("online", DataObject.empty().put("mode", "everyone")))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> service.saveConnection(c, DataObject.empty().put("online", DataObject.empty().put("limit", 0)))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> service.saveConnection(c, DataObject.empty().put("feed", DataObject.empty().put("limit", 26)))).status);
    }

    @Test
    void theConnectionsPageShowsTheServerTheProviderAndEachChannelsVerdict() {
        site(OnlineMode.NAMES_AVATARS, PRIVATE);
        when(guild.getTextChannels()).thenReturn(List.of(newsChannel, staffChannel));
        when(guild.getName()).thenReturn("Younglings");

        DataObject state = service.connectionState(admin());

        assertTrue(state.getObject("server").getBoolean("connected"));
        assertTrue(state.getObject("server").getBoolean("botPresent"));
        assertEquals("Younglings", state.getObject("server").getString("name"));
        assertEquals(SiteConnectionStore.RUNECLAN_BOT, state.getObject("provider").getString("id"));
        assertTrue(state.getObject("provider").getArray("capabilities").toList().contains(SiteDiscordService.PRESENCE));
        assertEquals("names+avatars", state.getObject("online").getString("mode"));
        DataObject staff = state.getObject("feed").getArray("chosen").getObject(0);
        assertFalse(staff.getBoolean("eligible"));
        assertTrue(staff.getString("reason").contains("private"));
        assertEquals(1, state.getObject("feed").getArray("channels").length(), "a chosen channel isn't offered twice");
    }

    @Test
    void onlyAServerAdminCanConnectAndOnlyTheSitesOwnServer() {
        SiteCaller c = admin();
        site(null, OnlineMode.OFF, 24, true, 5);
        DataObject own = DataObject.empty().put("guildId", Long.toString(GUILD));

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> service.connect(c, true, own)).status);
        when(c.member().hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> service.connect(c, true, DataObject.empty().put("guildId", "123456789012345678"))).status);
        verify(connections, never()).setConnected(anyLong(), eq(true), anyLong());

        service.connect(c, true, own);
        verify(connections).setConnected(GUILD, true, 5L);
        service.connect(c, false, DataObject.empty());
        verify(connections).setConnected(GUILD, false, 5L);
    }

    @Test
    void aMembersOwnChoiceClearsTheCachedList() {
        site(OnlineMode.NAMES);
        List<Member> members = List.of(member(11, "Ann", OnlineStatus.ONLINE));
        when(guild.getMembers()).thenReturn(members);
        when(settings.hiddenMembers(GUILD)).thenReturn(Set.of());
        assertTrue(service.online(guild).toString().contains("Ann"));

        when(settings.hiddenMembers(GUILD)).thenReturn(Set.of(11L));
        when(settings.isHidden(GUILD, 11L)).thenReturn(true);
        assertTrue(service.saveMemberChoice(guild, 11L, true).getBoolean("hidden"));

        verify(settings).setHidden(GUILD, 11L, true);
        assertFalse(service.online(guild).toString().contains("Ann"), "the next answer is fresh, not the cached one");
    }
}
