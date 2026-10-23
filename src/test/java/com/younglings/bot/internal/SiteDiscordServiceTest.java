package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteDiscordSettings;
import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.younglings.bot.config.IntentSwitches;
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

/** Discord on a clan website over a mock server (T-365): off by default, never more than the mode allows, and only public channels. */
class SiteDiscordServiceTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long PUBLIC = Long.parseLong("1".repeat(18));
    private static final long PRIVATE = Long.parseLong("2".repeat(18));
    private static final long ADULT = Long.parseLong("3".repeat(18));

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
        service = new SiteDiscordService(settings, news, profiles, new SiteCache());
        guild = mock(Guild.class);
        everyone = mock(Role.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getPublicRole()).thenReturn(everyone);
        when(guild.getSelfMember()).thenReturn(self);
        newsChannel = channel(PUBLIC, "news", false, true);
        staffChannel = channel(PRIVATE, "staff", false, false);
        channel(ADULT, "memes", true, true);
        when(settings.onlineMode(GUILD)).thenReturn(OnlineMode.OFF);
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
        when(settings.onlineMode(GUILD)).thenReturn(OnlineMode.NAMES);
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
        when(settings.onlineMode(GUILD)).thenReturn(OnlineMode.COUNT);

        assertTrue(service.online(guild).getBoolean("unavailable"));
    }

    // ---------- feed ----------

    private void chosen(long... ids) {
        List<SiteNewsService.NewsChannel> list = new java.util.ArrayList<>();
        for (long id : ids) list.add(new SiteNewsService.NewsChannel(id, null, list.size()));
        when(news.channels(GUILD)).thenReturn(list);
    }

    @Test
    void theFeedOnlyReadsChannelsThatAreStillPublicAndDropsAvatars() {
        chosen(PUBLIC, PRIVATE, ADULT); // the two bad ones were chosen before the rules, or made private later
        DataObject post = DataObject.empty().put("id", "1").put("author", "Ann").put("avatarUrl", "https://cdn.example/a.png").put("text", "hi");
        when(news.latest(eq(guild), eq(List.of(new SiteNewsService.NewsChannel(PUBLIC, null, 0))), anyInt())).thenReturn(DataArray.empty().add(post));

        DataObject feed = service.feed(guild, null, 10);

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

        assertNull(service.feed(guild, Long.toString(PRIVATE), 10));
        assertNull(service.feed(guild, Long.toString(ADULT), 10));
        assertEquals(0, service.feed(guild, Long.toString(PUBLIC), 10).getArray("posts").length());
    }

    @Test
    void theFeedAsksForAtMost25Posts() {
        chosen(PUBLIC);
        when(news.latest(eq(guild), any(), anyInt())).thenReturn(DataArray.empty());

        service.feed(guild, null, 500);

        verify(news).latest(eq(guild), any(), eq(25));
    }

    @Test
    void withoutMessageContentTheFeedIsUnavailable() {
        IntentSwitches.set(true, false);
        chosen(PUBLIC);

        DataObject feed = service.feed(guild, null, 10);

        assertTrue(feed.getBoolean("unavailable"));
        verify(news, never()).latest(any(), any(), anyInt());
    }

    // ---------- dashboard ----------

    @Test
    void aPrivateOrAgeRestrictedChannelCantBeOptedIn() {
        Member actor = mock(Member.class);
        for (long bad : new long[]{PRIVATE, ADULT}) {
            DataObject body = DataObject.empty().put("onlineMode", "names")
                    .put("channels", DataArray.empty().add(DataObject.empty().put("channelId", Long.toString(bad))));
            TicketAdminApi.ApiError error = assertThrows(TicketAdminApi.ApiError.class, () -> service.save(guild, actor, body));
            assertEquals(403, error.status);
        }
        verify(news, never()).replaceChannels(anyLong(), any());
        verify(settings, never()).saveOnlineMode(anyLong(), any(), anyLong());
    }

    @Test
    void savingAModeAndPublicChannelsKeepsLeftOutFields() {
        Member actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(5L);

        service.save(guild, actor, DataObject.empty().put("onlineMode", "count"));
        verify(settings).saveOnlineMode(GUILD, OnlineMode.COUNT, 5L);
        verify(news, never()).replaceChannels(anyLong(), any());

        service.save(guild, actor, DataObject.empty().put("channels", DataArray.empty().add(DataObject.empty().put("channelId", Long.toString(PUBLIC)).put("label", "News"))));
        verify(news).replaceChannels(GUILD, List.of(new SiteNewsService.NewsChannel(PUBLIC, "News", 0)));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> service.save(guild, actor, DataObject.empty().put("onlineMode", "everyone"))).status);
    }

    @Test
    void theSettingsPageShowsEachChannelsVerdict() {
        chosen(PRIVATE);
        when(guild.getTextChannels()).thenReturn(List.of(newsChannel, staffChannel));
        when(settings.onlineMode(GUILD)).thenReturn(OnlineMode.NAMES_AVATARS);

        DataObject state = service.adminState(guild);

        assertEquals("names+avatars", state.getString("onlineMode"));
        assertTrue(state.getBoolean("presenceAvailable"));
        DataObject staff = state.getArray("chosen").getObject(0);
        assertFalse(staff.getBoolean("eligible"));
        assertTrue(staff.getString("reason").contains("private"));
        assertEquals(1, state.getArray("channels").length(), "a chosen channel isn't offered twice");
        assertTrue(state.getArray("channels").getObject(0).getBoolean("eligible"));
    }

    @Test
    void aMembersOwnChoiceClearsTheCachedList() {
        when(settings.onlineMode(GUILD)).thenReturn(OnlineMode.NAMES);
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
