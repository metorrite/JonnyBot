package com.younglings.bot.internal;

import com.younglings.bot.eventannounce.EventAnnounceRepository;
import com.younglings.bot.eventannounce.EventAnnounceRules.Settings;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventAnnounceAdminApiTest {
    private EventAnnounceRepository repository;
    private EventAnnounceAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;
    private GuildMessageChannel channel;

    @BeforeEach
    void setUp() {
        repository = mock(EventAnnounceRepository.class);
        when(repository.settings(5L)).thenReturn(Settings.defaults(5L));
        when(repository.recent(5L, 10)).thenReturn(List.of());
        api = new EventAnnounceAdminApi(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        self = mock(SelfMember.class);
        when(self.hasPermission(Permission.MANAGE_ROLES)).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        when(guild.getRoles()).thenReturn(List.of());
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(42L);
        when(channel.getGuild()).thenReturn(guild);
        when(self.hasPermission(any(GuildMessageChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, 42L)).thenReturn(channel);
    }

    private static DataObject body(boolean enabled, String channel, boolean useRole) {
        return DataObject.empty().put("enabled", enabled).put("channelId", channel).put("useRole", useRole).put("notifyStart", true);
    }

    @Test
    void theDefaultIsOffWithNoChannel() {
        DataObject got = api.get(guild);
        assertFalse(got.getBoolean("enabled"));
        assertTrue(got.isNull("channelId"));
        assertEquals(250, got.getInt("roleLimit"));
        assertTrue(got.getBoolean("roomForRoles"));
    }

    @Test
    void goodSettingsAreSaved() {
        api.save(guild, actor, body(true, "42", true));
        ArgumentCaptor<Settings> saved = ArgumentCaptor.forClass(Settings.class);
        verify(repository).saveSettings(saved.capture());
        assertTrue(saved.getValue().enabled());
        assertEquals(42L, saved.getValue().channelId());
        assertTrue(saved.getValue().useRole());
    }

    @Test
    void turningItOnNeedsAChannelThatExistsAndCanBePostedIn() {
        assertEquals(1, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body(true, "", false))).problems.size());
        assertTrue(assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body(true, "99", false))).problems.get(0).contains("isn"));
        when(self.hasPermission(any(GuildMessageChannel.class), any(Permission[].class))).thenReturn(false);
        assertTrue(assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body(true, "42", false))).problems.get(0).contains("Embed Links"));
        verify(repository, never()).saveSettings(any());
    }

    @Test
    void theRoleNeedsManageRoles() {
        when(self.hasPermission(Permission.MANAGE_ROLES)).thenReturn(false);
        assertTrue(assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body(true, "42", true))).problems.get(0).contains("Manage Roles"));
        api.save(guild, actor, body(true, "42", false));
        verify(repository).saveSettings(any());
    }

    @Test
    void turningItOffNeedsNoChannel() {
        api.save(guild, actor, body(false, "", false));
        verify(repository).saveSettings(any());
    }

    @Test
    void otherRoutesAreNotThisOnes() {
        assertEquals(null, api.route(guild, actor, "GET", new String[]{"events"}, DataObject::empty));
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"eventannounce"}, DataObject::empty)).status);
    }
}
