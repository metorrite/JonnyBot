package com.runeclans.bot.internal;

import com.runeclans.bot.kick.KickClient;
import com.runeclans.bot.kick.KickRepository;
import com.runeclans.bot.kick.KickRepository.Settings;
import com.runeclans.bot.kick.KickRepository.Streamer;
import com.runeclans.bot.kick.KickRules.OfflineAction;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KickAdminApiTest {
    private static final long GUILD = 10;

    private KickRepository repository;
    private KickClient client;
    private KickAdminApi api;
    private Guild guild;
    private Member actor;
    private TextChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(KickRepository.class);
        client = mock(KickClient.class);
        api = new KickAdminApi(repository, client);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        actor = mock(Member.class);
        channel = mock(TextChannel.class);
        net.dv8tion.jda.api.entities.SelfMember self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(GuildMessageChannel.class), any(Permission[].class))).thenReturn(true);
        when(channel.getGuild()).thenReturn(guild);
        when(guild.getChannelById(GuildMessageChannel.class, 500L)).thenReturn(channel);
        when(repository.settings(GUILD)).thenReturn(Settings.defaults());
        when(repository.streamers(GUILD)).thenReturn(List.of());
        when(client.configured()).thenReturn(true);
        when(client.user("bob")).thenReturn(Optional.of(new KickClient.User("bob", "Bob", "")));
    }

    private static DataObject newStreamer() {
        return DataObject.empty().put("login", "https://kick.com/Bob").put("channelId", "500").put("message", "  Bob is on!  ");
    }

    @Test
    void itIsOffByDefaultAndSaysWhetherTheBotHasAKickApp() {
        DataObject list = api.list(guild);

        assertFalse(list.getBoolean("enabled"));
        assertTrue(list.getBoolean("configured"));
        assertEquals("KICK_CLIENT_ID", list.getArray("envNames").getString(0));
    }

    @Test
    void followingAddsTheCanonicalLoginAndName() {
        when(repository.add(anyLong(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(Optional.of(mock(Streamer.class)));

        api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, KickAdminApiTest::newStreamer);

        verify(repository).add(GUILD, "bob", "Bob", 500L, "Bob is on!", null);
    }

    @Test
    void aChannelNobodyOwnsOnKickIsNotFound() throws Exception {
        when(client.user("bob")).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, KickAdminApiTest::newStreamer)).status);
        verify(repository, never()).add(anyLong(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void withoutAKickAppNothingCanBeFollowedOrSwitchedOn() {
        when(client.configured()).thenReturn(false);

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, KickAdminApiTest::newStreamer)).status);
        DataObject on = DataObject.empty().put("enabled", true).put("offlineAction", "EDIT");
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"kick", "settings"}, () -> on)).status);
        verify(repository, never()).saveSettings(anyLong(), any());
    }

    @Test
    void aChannelFromAnotherServerOrATooLongMessageIsRefused() {
        DataObject foreign = newStreamer().put("channelId", "999");
        DataObject wordy = newStreamer().put("message", "x".repeat(301));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, () -> foreign)).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, () -> wordy)).status);
        verify(repository, never()).add(anyLong(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void aRoleFromAnotherServerIsRefusedAndOneFromThisServerIsKept() {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(77L);
        when(guild.getRoleById(77L)).thenReturn(role);
        when(repository.add(anyLong(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(Optional.of(mock(Streamer.class)));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, () -> newStreamer().put("pingRoleId", "78"))).status);
        api.route(guild, actor, "POST", new String[]{"kick", "streamers"}, () -> newStreamer().put("pingRoleId", "77"));

        verify(repository).add(GUILD, "bob", "Bob", 500L, "Bob is on!", 77L);
    }

    @Test
    void theSwitchAndWhatHappensWhenAStreamEndsAreSaved() {
        api.route(guild, actor, "PUT", new String[]{"kick", "settings"}, () -> DataObject.empty().put("enabled", true).put("offlineAction", "delete"));

        verify(repository).saveSettings(GUILD, new Settings(true, OfflineAction.DELETE));
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"kick", "settings"}, () -> DataObject.empty().put("offlineAction", "explode"))).status);
    }

    @Test
    void aStreamerOfAnotherServerCannotBeChangedOrRemoved() {
        when(repository.get(eq(GUILD), eq(5L))).thenReturn(Optional.empty());
        when(repository.delete(GUILD, 5L)).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"kick", "streamers", "5"}, DataObject::empty)).status);
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"kick", "streamers", "5"}, DataObject::empty)).status);
        verify(repository, never()).update(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void otherRoutesAreNotThisExtensions() {
        assertNull(api.route(guild, actor, "GET", new String[]{"feeds"}, DataObject::empty));
    }
}
