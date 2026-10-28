package com.runeclans.bot.internal;

import com.runeclans.bot.autodelete.AutoDeleteRepository;
import com.runeclans.bot.autodelete.AutoDeleteRule;
import com.runeclans.bot.autodelete.AutoDeleteRule.Filter;
import com.runeclans.bot.autodelete.AutoDeleteRule.RoleMode;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoDeleteAdminApiTest {
    private AutoDeleteRepository repository;
    private AutoDeleteAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(AutoDeleteRepository.class);
        api = new AutoDeleteAdminApi(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(any(GuildMessageChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.getIdLong()).thenReturn(9L);
        when(channel.getName()).thenReturn("general");
        when(guild.getChannelById(GuildMessageChannel.class, 9L)).thenReturn(channel);
        Role role = mock(Role.class);
        when(guild.getRoleById(70L)).thenReturn(role);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(repository.forGuild(5L)).thenReturn(List.of());
    }

    private AutoDeleteRule saved() {
        ArgumentCaptor<AutoDeleteRule> captor = ArgumentCaptor.forClass(AutoDeleteRule.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static DataObject json(String text) {
        return DataObject.fromJson(text);
    }

    @Test
    void wordsRoleConditionAndDelayAreSaved() {
        api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [\"LINKS\"], \"words\": [\" Free Nitro \", \"scam\", \"SCAM\"], \"roleMode\": \"EXCEPT_WITH\", \"roleIds\": [\"70\"], \"delaySeconds\": 30}"));

        AutoDeleteRule rule = saved();
        assertEquals(EnumSet.of(Filter.LINKS, Filter.TEXT), rule.filters(), "words switch the text filter on");
        assertEquals(List.of("Free Nitro", "scam"), rule.words());
        assertEquals(RoleMode.EXCEPT_WITH, rule.roleMode());
        assertEquals(Set.of(70L), rule.roleIds());
        assertEquals(30, rule.delaySeconds());
    }

    @Test
    void aRuleSavedTheOldWayIsForEveryoneAndImmediate() {
        api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [\"INVITES\"]}"));

        AutoDeleteRule rule = saved();
        assertEquals(RoleMode.EVERYONE, rule.roleMode());
        assertEquals(0, rule.delaySeconds());
        assertTrue(rule.words().isEmpty());
    }

    @Test
    void aTooShortWordRefusesTheSave() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [], \"words\": [\"hi\"]}")));

        assertEquals(400, error.status);
        verify(repository, never()).save(any());
    }

    @Test
    void theDelayHasALimit() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [\"LINKS\"], \"delaySeconds\": 301}")));

        assertEquals(400, error.status);
    }

    @Test
    void aRoleConditionNeedsARoleAndTheRoleMustExist() {
        var noRole = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [\"LINKS\"], \"roleMode\": \"ONLY_WITH\", \"roleIds\": []}")));
        var missing = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [\"LINKS\"], \"roleMode\": \"ONLY_WITH\", \"roleIds\": [\"71\"]}")));

        assertEquals(400, noRole.status);
        assertEquals(400, missing.status);
        verify(repository, never()).save(any());
    }

    @Test
    void theTextFilterWithoutWordsIsRefused() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"channelId\": \"9\", \"filters\": [\"TEXT\"]}")));

        assertEquals(400, error.status);
    }
}
