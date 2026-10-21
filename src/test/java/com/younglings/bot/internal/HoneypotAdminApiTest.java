package com.younglings.bot.internal;

import com.younglings.bot.honeypot.HoneypotConfig;
import com.younglings.bot.honeypot.HoneypotRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HoneypotAdminApiTest {
    private static final long GUILD = 10;
    private HoneypotRepository repository;
    private HoneypotAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(HoneypotRepository.class);
        api = new HoneypotAdminApi(repository);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn("10");
        when(guild.getSelfMember()).thenReturn(mock(SelfMember.class));
        when(actor.getId()).thenReturn("5");
        when(repository.get(GUILD)).thenReturn(HoneypotConfig.defaults(GUILD));
        TextChannel channel = mock(TextChannel.class);
        when(guild.getChannelById(TextChannel.class, 100L)).thenReturn(channel);
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(200L);
        when(guild.getRoleById(200L)).thenReturn(role);
        Role managed = mock(Role.class);
        when(managed.isManaged()).thenReturn(true);
        when(guild.getRoleById(201L)).thenReturn(managed);
        Role everyone = mock(Role.class);
        when(everyone.isPublicRole()).thenReturn(true);
        when(guild.getRoleById(10L)).thenReturn(everyone);
        Role staff = mock(Role.class);
        when(staff.getIdLong()).thenReturn(300L);
        when(guild.getRoleById(300L)).thenReturn(staff);
    }

    private DataObject put(String json) {
        return api.route(guild, actor, "PUT", new String[]{"honeypot"}, () -> DataObject.fromJson(json));
    }

    @Test
    void theDefaultAnswerIsOffWithTheReversibleAction() {
        DataObject out = api.route(guild, actor, "GET", new String[]{"honeypot"}, DataObject::empty);
        assertFalse(out.getBoolean("enabled"));
        assertEquals("TIMEOUT", out.getString("action"));
    }

    @Test
    void aGoodSettingIsSaved() {
        put("{\"enabled\":true,\"channelId\":\"100\",\"roleId\":\"200\",\"action\":\"kick\",\"exemptRoleIds\":[\"300\"]}");
        ArgumentCaptor<HoneypotConfig> saved = ArgumentCaptor.forClass(HoneypotConfig.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().enabled());
        assertEquals(100L, saved.getValue().channelId());
        assertEquals(200L, saved.getValue().roleId());
        assertEquals(HoneypotConfig.Action.KICK, saved.getValue().action());
        assertEquals(java.util.List.of(300L), saved.getValue().exemptRoleIds());
    }

    @Test
    void switchingOnWithNothingChosenIsRefused() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> put("{\"enabled\":true}"));
        assertTrue(thrown.getMessage().contains("can't be saved"));
        verify(repository, never()).save(any());
    }

    @Test
    void badChannelsRolesActionsAndOverlapsRefuseTheWholeSave() {
        for (String bad : new String[]{
                "{\"channelId\":\"999\"}", "{\"roleId\":\"999\"}", "{\"roleId\":\"201\"}", "{\"roleId\":\"10\"}", "{\"action\":\"nuke\"}",
                "{\"action\":\"timeout\",\"timeoutMinutes\":0}", "{\"action\":\"ban\",\"deleteDays\":9}", "{\"enabled\":true,\"roleId\":\"200\",\"exemptRoleIds\":[\"200\"]}",
                "{\"exemptRoleIds\":[\"12345\"]}"}) {
            assertThrows(TicketAdminApi.ApiError.class, () -> put(bad), bad);
        }
        verify(repository, never()).save(any());
    }
}
