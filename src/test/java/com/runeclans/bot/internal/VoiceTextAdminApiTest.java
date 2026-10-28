package com.runeclans.bot.internal;

import com.runeclans.bot.voicetext.VoiceTextRepository;
import com.runeclans.bot.voicetext.VoiceTextRepository.Config;
import com.runeclans.bot.voicetext.VoiceTextService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceTextAdminApiTest {
    private static final long GUILD = 5L;
    private VoiceTextRepository repository;
    private VoiceTextService service;
    private VoiceTextAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        repository = mock(VoiceTextRepository.class);
        service = mock(VoiceTextService.class);
        api = new VoiceTextAdminApi(repository, service);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        self = mock(SelfMember.class);
        when(self.hasPermission(any(TextChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(guild.getPublicRole()).thenReturn(mock(net.dv8tion.jda.api.entities.Role.class));
        when(repository.config(GUILD)).thenReturn(Config.defaults(GUILD));
        when(repository.links(GUILD)).thenReturn(Map.of());
        voice(1, "Raids");
        voice(2, "Chill");
        text(11, "raids-chat");
        text(12, "chill-chat");
    }

    private void voice(long id, String name) {
        VoiceChannel v = mock(VoiceChannel.class);
        when(v.getIdLong()).thenReturn(id);
        when(v.getName()).thenReturn(name);
        when(guild.getVoiceChannelById(id)).thenReturn(v);
    }

    private void text(long id, String name) {
        TextChannel t = mock(TextChannel.class);
        when(t.getIdLong()).thenReturn(id);
        when(t.getName()).thenReturn(name);
        when(guild.getTextChannelById(id)).thenReturn(t);
    }

    @Test
    void everythingIsOffUntilTheServerSwitchesItOn() {
        assertEquals(false, Config.defaults(GUILD).enabled());
        assertEquals(false, Config.defaults(GUILD).announce());
        assertEquals(false, Config.defaults(GUILD).purgeWhenEmpty());
    }

    @Test
    void pairsAreSavedAndTextChannelsNoLongerPairedLoseTheAccessThatWasGiven() {
        when(repository.links(GUILD)).thenReturn(Map.of(2L, 12L));

        api.save(guild, actor, DataObject.fromJson("{\"enabled\": true, \"links\": [{\"voiceChannelId\": \"1\", \"textChannelId\": \"11\"}]}"));

        verify(repository).saveLinks(GUILD, Map.of(1L, 11L));
        verify(repository).saveConfig(new Config(GUILD, true, false, false));
        verify(service).releaseAll(guild, 12L);
    }

    @Test
    void oneTextChannelCannotServeTwoVoiceChannels() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"links\": [{\"voiceChannelId\": \"1\", \"textChannelId\": \"11\"}, {\"voiceChannelId\": \"2\", \"textChannelId\": \"11\"}]}")));

        assertTrue(error.problems.get(0).contains("more than one voice channel"));
        verify(repository, never()).saveLinks(any(Long.class), any());
    }

    @Test
    void switchingOnNeedsAPairAndJonnyBotNeedsPermissionInTheTextChannel() {
        var noPairs = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.fromJson("{\"enabled\": true}")));
        assertEquals(400, noPairs.status);

        when(self.hasPermission(any(TextChannel.class), any(Permission[].class))).thenReturn(false);
        var noPermission = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"links\": [{\"voiceChannelId\": \"1\", \"textChannelId\": \"11\"}]}")));
        assertTrue(noPermission.problems.get(0).contains("Manage Permissions"));
        verify(repository, never()).saveConfig(any());
    }
}
