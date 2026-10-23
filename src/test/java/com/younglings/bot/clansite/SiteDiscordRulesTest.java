package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteDiscordRules.Group;
import com.younglings.bot.clansite.SiteDiscordRules.Person;
import com.younglings.bot.clansite.SiteDiscordRules.Refusal;
import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The privacy rules of Discord on a clan website (T-365): what each online mode shows, and which channels may feed a public page. */
class SiteDiscordRulesTest {
    private static final Group OFFICERS = new Group(2, "Officers", 0xFF0000, 10);
    private static final Group MEMBERS = new Group(3, "Members", 0x00FF00, 5);

    private static Person person(long id, String name, OnlineStatus status, Group group) {
        return new Person(id, false, status, group, name, 0x123456, "https://cdn.example/avatars/" + id + ".png");
    }

    private final List<Person> people = new ArrayList<>(List.of(
            person(1, "zed", OnlineStatus.ONLINE, MEMBERS),
            person(2, "Amy", OnlineStatus.IDLE, MEMBERS),
            person(3, "Boss", OnlineStatus.DO_NOT_DISTURB, OFFICERS),
            person(4, "loner", OnlineStatus.ONLINE, Group.ONLINE),
            person(5, "asleep", OnlineStatus.OFFLINE, MEMBERS),
            person(6, "ghost", OnlineStatus.INVISIBLE, MEMBERS),
            new Person(7, true, OnlineStatus.ONLINE, OFFICERS, "SomeBot", 0, null)));

    @Test
    void countGivesTheNumberOnly() {
        DataObject answer = SiteDiscordRules.online(people, OnlineMode.COUNT, Set.of());

        assertEquals(4, answer.getInt("total"), "bots, offline and invisible members are never counted");
        assertEquals("count", answer.getString("mode"));
        assertFalse(answer.hasKey("groups"));
        assertFalse(answer.toString().contains("zed"));
    }

    @Test
    void namesAreGroupedByHoistedRoleWithNoAvatarsAndNoIds() {
        DataObject answer = SiteDiscordRules.online(people, OnlineMode.NAMES, Set.of());
        DataArray groups = answer.getArray("groups");

        assertEquals(3, groups.length());
        assertEquals("Officers", groups.getObject(0).getString("name"));
        assertEquals("Members", groups.getObject(1).getString("name"));
        assertEquals("Online", groups.getObject(2).getString("name"));
        DataArray members = groups.getObject(1).getArray("members");
        assertEquals("Amy", members.getObject(0).getString("displayName"), "alphabetical, ignoring case");
        assertEquals("zed", members.getObject(1).getString("displayName"));
        String json = answer.toString();
        assertFalse(json.contains("avatarUrl"));
        assertFalse(json.contains("SomeBot"));
        assertFalse(json.contains("ghost"));
        assertFalse(json.contains("\"id\""));
        assertFalse(json.contains("userId"));
    }

    @Test
    void avatarsOnlyInNamesAndAvatars() {
        DataObject answer = SiteDiscordRules.online(people, OnlineMode.NAMES_AVATARS, Set.of());
        String avatar = answer.getArray("groups").getObject(0).getArray("members").getObject(0).getString("avatarUrl");

        assertEquals("https://cdn.example/avatars/3.png?size=64", avatar);
    }

    @Test
    void aHiddenMemberIsCountedButNeverNamedNorPlacedInAGroup() {
        DataObject answer = SiteDiscordRules.online(people, OnlineMode.NAMES_AVATARS, Set.of(3L));

        assertEquals(4, answer.getInt("total"));
        assertEquals(3, answer.getInt("named"));
        assertFalse(answer.toString().contains("Boss"));
        assertFalse(answer.toString().contains("Officers"), "a group only holding hidden members isn't shown either");
    }

    @Test
    void namesAreCapped() {
        List<Person> crowd = new ArrayList<>();
        for (int i = 0; i < SiteDiscordRules.MAX_NAMED + 50; i++) crowd.add(person(100 + i, "m" + i, OnlineStatus.ONLINE, Group.ONLINE));
        DataObject answer = SiteDiscordRules.online(crowd, OnlineMode.NAMES, Set.of());

        assertEquals(SiteDiscordRules.MAX_NAMED + 50, answer.getInt("total"));
        assertEquals(SiteDiscordRules.MAX_NAMED, answer.getInt("named"));
    }

    @Test
    void withoutPresenceTheAnswerSaysUnavailable() {
        DataObject answer = SiteDiscordRules.onlineUnavailable(OnlineMode.NAMES);
        assertTrue(answer.getBoolean("unavailable"));
        assertEquals(0, answer.getInt("total"));
    }

    @Test
    void modesParseFromTheirKeysOnly() {
        assertEquals(OnlineMode.NAMES_AVATARS, OnlineMode.parse("names+avatars").orElseThrow());
        assertEquals(OnlineMode.OFF, OnlineMode.parse("off").orElseThrow());
        assertTrue(OnlineMode.parse("NAMES").isEmpty());
        assertTrue(OnlineMode.parse(null).isEmpty());
    }

    // ---------- channels ----------

    private Guild guild;
    private Role everyone;
    private SelfMember self;

    @BeforeEach
    void setUpGuild() {
        guild = mock(Guild.class);
        everyone = mock(Role.class);
        self = mock(SelfMember.class);
        when(guild.getPublicRole()).thenReturn(everyone);
        when(guild.getSelfMember()).thenReturn(self);
    }

    private TextChannel channel(String name, boolean nsfw, boolean everyoneReads, boolean botReads) {
        TextChannel channel = mock(TextChannel.class);
        when(channel.getName()).thenReturn(name);
        when(channel.isNSFW()).thenReturn(nsfw);
        when(everyone.hasPermission(eq(channel), eq(Permission.VIEW_CHANNEL), eq(Permission.MESSAGE_HISTORY))).thenReturn(everyoneReads);
        when(self.hasPermission(eq(channel), eq(Permission.VIEW_CHANNEL), eq(Permission.MESSAGE_HISTORY))).thenReturn(botReads);
        return channel;
    }

    @Test
    void aPublicChannelTheBotCanReadPasses() {
        assertNull(SiteDiscordRules.channelRefusal(guild, channel("news", false, true, true)));
    }

    @Test
    void privateAgeRestrictedAndThreadChannelsAreForbidden() {
        Refusal hidden = SiteDiscordRules.channelRefusal(guild, channel("staff", false, false, true));
        assertTrue(hidden.forbidden());
        assertTrue(hidden.reason().contains("#staff is private"));

        Refusal nsfw = SiteDiscordRules.channelRefusal(guild, channel("memes", true, true, true));
        assertTrue(nsfw.forbidden());
        assertTrue(nsfw.reason().contains("age-restricted"));

        ThreadChannel thread = mock(ThreadChannel.class);
        when(everyone.hasPermission(any(ThreadChannel.class), any(Permission.class), any(Permission.class))).thenReturn(true);
        assertTrue(SiteDiscordRules.channelRefusal(guild, thread).forbidden());
    }

    @Test
    void aChannelTheBotCantReadIsFixableNotForbidden() {
        Refusal refusal = SiteDiscordRules.channelRefusal(guild, channel("news", false, true, false));
        assertNotNull(refusal);
        assertFalse(refusal.forbidden());
        assertFalse(SiteDiscordRules.channelRefusal(guild, null).forbidden());
    }
}
