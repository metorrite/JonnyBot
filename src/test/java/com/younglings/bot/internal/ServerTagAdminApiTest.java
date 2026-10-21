package com.younglings.bot.internal;

import com.younglings.bot.servertag.ServerTagConfig;
import com.younglings.bot.servertag.ServerTagRepository;
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

class ServerTagAdminApiTest {
    private static final long GUILD = 10;
    private ServerTagRepository repository;
    private ServerTagAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(ServerTagRepository.class);
        api = new ServerTagAdminApi(repository);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn("10");
        when(guild.getSelfMember()).thenReturn(mock(SelfMember.class));
        when(actor.getId()).thenReturn("5");
        when(repository.get(GUILD)).thenReturn(ServerTagConfig.defaults(GUILD));
        when(guild.getChannelById(GuildMessageChannel.class, 500L)).thenReturn(mock(GuildMessageChannel.class));
    }

    private DataObject put(String json) {
        return api.route(guild, actor, "PUT", new String[]{"servertag"}, () -> DataObject.fromJson(json));
    }

    @Test
    void theDefaultAnswerIsOffAndAlertOnly() {
        DataObject out = api.route(guild, actor, "GET", new String[]{"servertag"}, DataObject::empty);
        assertFalse(out.getBoolean("enabled"));
        assertEquals("ALERT", out.getString("action"));
    }

    @Test
    void aGoodSettingIsSaved() {
        put("{\"enabled\":true,\"flaggedGuildIds\":[\"123456789012345678\",\"123456789012345678\"],\"action\":\"alert\",\"alertChannelId\":\"500\"}");
        ArgumentCaptor<ServerTagConfig> saved = ArgumentCaptor.forClass(ServerTagConfig.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().enabled());
        assertEquals(List.of(123456789012345678L), saved.getValue().flaggedGuildIds());
        assertEquals(500L, saved.getValue().alertChannelId());
    }

    @Test
    void badIdsMissingChannelsAndEmptyListsRefuseTheWholeSave() {
        for (String bad : new String[]{
                "{\"enabled\":true}", "{\"flaggedGuildIds\":[\"abc\"]}", "{\"flaggedGuildIds\":[\"12\"]}", "{\"action\":\"nuke\"}", "{\"alertChannelId\":\"999\"}",
                "{\"enabled\":true,\"flaggedGuildIds\":[\"123456789012345678\"],\"action\":\"alert\"}",
                "{\"flaggedGuildIds\":[\"10\"]}"}) {
            assertThrows(TicketAdminApi.ApiError.class, () -> put(bad), bad);
        }
        verify(repository, never()).save(any());
    }
}
