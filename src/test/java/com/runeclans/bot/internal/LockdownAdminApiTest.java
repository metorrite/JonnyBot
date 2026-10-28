package com.runeclans.bot.internal;

import com.runeclans.bot.lockdown.LockdownRepository.Config;
import com.runeclans.bot.lockdown.LockdownService;
import com.runeclans.bot.permission.DashboardAccess;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LockdownAdminApiTest {
    private static final long GUILD = 5L;
    private LockdownService service;
    private DashboardAccess access;
    private com.runeclans.bot.discord.jobs.DiscordOutbox outbox;
    private LockdownAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        service = mock(LockdownService.class);
        access = mock(DashboardAccess.class);
        outbox = com.runeclans.bot.testsupport.QueuedJobs.outbox();
        api = new LockdownAdminApi(service, access, outbox);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.ADMIN);
        when(service.config(GUILD)).thenReturn(Config.defaults(GUILD));
    }

    private static DataObject json(String text) {
        return DataObject.fromJson(text);
    }

    @Test
    void onlyAnAdminCanLockOrChangeTheSet() {
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"lockdown", "start"}, () -> json("{}")));

        assertEquals(403, error.status);
        verify(service, never()).start(any(), anyLong(), any());
    }

    @Test
    void aSetOfChannelsJonnyBotCannotManageIsRefused() {
        StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
        when(channel.getName()).thenReturn("general");
        when(guild.getChannelById(StandardGuildMessageChannel.class, 9L)).thenReturn(channel);
        // canManage is a static check on the bot's permissions in that channel: the mock guild has no self member, so it fails closed
        when(guild.getSelfMember()).thenReturn(mock(net.dv8tion.jda.api.entities.SelfMember.class));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"channelIds\": [\"9\"]}")));

        assertEquals(400, error.status);
        verify(service, never()).saveConfig(any());
    }

    @Test
    void everyoneCannotBeTheRoleThatKeepsTalking() {
        Role everyone = mock(Role.class);
        when(everyone.isPublicRole()).thenReturn(true);
        when(guild.getRoleById(5L)).thenReturn(everyone);

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, json("{\"keepTalkingRoleIds\": [\"5\"]}")));

        assertTrue(error.problems.get(0).contains("@everyone"));
    }

    @Test
    void aSetWithNoChannelsCannotBeStarted() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"lockdown", "start"}, () -> json("{}")));

        assertEquals(400, error.status);
        verify(service, never()).start(any(), anyLong(), any());
    }

    @Test
    void anEmptySavedSetIsSavedAsIs() {
        when(service.config(GUILD)).thenReturn(new Config(GUILD, List.of(1L), List.of()));

        api.save(guild, actor, json("{\"channelIds\": [], \"keepTalkingRoleIds\": []}"));

        verify(service).saveConfig(new Config(GUILD, List.of(), List.of()));
    }

    private StandardGuildMessageChannel manageable(long id) {
        StandardGuildMessageChannel channel = mock(StandardGuildMessageChannel.class);
        when(channel.getName()).thenReturn("general");
        when(channel.getIdLong()).thenReturn(id);
        when(channel.getGuild()).thenReturn(guild);
        when(guild.getChannelById(StandardGuildMessageChannel.class, id)).thenReturn(channel);
        net.dv8tion.jda.api.entities.SelfMember self = mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(self.hasPermission(org.mockito.ArgumentMatchers.eq(channel), any(net.dv8tion.jda.api.Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        return channel;
    }

    @Test
    void lockingIsQueuedAsADiscordJobAndTheAnswerComesAtOnce() {
        manageable(9L);

        DataObject answer = api.route(guild, actor, "POST", new String[]{"lockdown", "lock"}, () -> json("{\"channelId\": \"9\", \"reason\": \"raid\"}"));

        assertEquals("77", answer.getObject("job").getString("id"));
        verify(outbox).enqueue(org.mockito.ArgumentMatchers.eq(LockdownJobs.LOCK), org.mockito.ArgumentMatchers.eq(GUILD), anyLong(), org.mockito.ArgumentMatchers.eq("lockdown-lock:" + GUILD + ":9"), any());
        verify(service, never()).lock(any(), any(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aChannelThatIsAlreadyLockedIsRefusedWithoutQueueing() {
        manageable(9L);
        when(service.locks(GUILD)).thenReturn(List.of(new com.runeclans.bot.lockdown.LockdownRepository.Lock(GUILD, 9L, 7L, java.time.Instant.EPOCH, "", false, "")));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"lockdown", "lock"}, () -> json("{\"channelId\": \"9\"}")));

        assertEquals(409, error.status);
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void unlockingAChannelThatIsNotLockedIsRefusedWithoutQueueing() {
        manageable(9L);

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"lockdown", "unlock"}, () -> json("{\"channelId\": \"9\"}")));

        assertEquals(409, error.status);
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aLockdownIsQueuedAsOneJob() {
        when(service.config(GUILD)).thenReturn(new Config(GUILD, List.of(1L, 2L), List.of()));

        DataObject answer = api.route(guild, actor, "POST", new String[]{"lockdown", "start"}, () -> json("{}"));

        assertEquals("77", answer.getObject("job").getString("id"));
        verify(service, never()).start(any(), anyLong(), any());
    }
}
