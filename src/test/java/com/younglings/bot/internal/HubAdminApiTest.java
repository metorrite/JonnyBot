package com.younglings.bot.internal;

import com.younglings.bot.hub.HubCommand;
import com.younglings.bot.hub.HubService;
import com.younglings.bot.hub.HubSettings;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubAdminApiTest {
    private static final long GUILD = 1L;

    private HubService hub;
    private PermissionGroupService groups;
    private DashboardAccess access;
    private HubAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        hub = mock(HubService.class);
        groups = mock(PermissionGroupService.class);
        access = mock(DashboardAccess.class);
        api = new HubAdminApi(hub, groups, access);

        guild = mock(Guild.class);
        actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.ADMIN);
        when(hub.get(GUILD, HubCommand.SIGNUP)).thenReturn(HubSettings.defaults(GUILD, "signup"));
        when(hub.signupPolicy(GUILD)).thenReturn(HubService.SignupPolicy.NONE);
        when(groups.groups(GUILD)).thenReturn(List.of(
                new PermissionGroup(1, GUILD, "admin", "Admin", true, true, List.of()),
                new PermissionGroup(2, GUILD, "cweb", "Web Dev", false, false, List.of(5L))));
    }

    private GuildMessageChannel channel(long id, boolean canTalk) {
        TextChannel channel = mock(TextChannel.class);
        when(guild.getTextChannelById(id)).thenReturn(channel);
        when(channel.getIdLong()).thenReturn(id);
        when(channel.getName()).thenReturn("chan" + id);
        when(channel.canTalk()).thenReturn(canTalk);
        when(guild.getChannelById(GuildMessageChannel.class, id)).thenReturn(channel);
        return channel;
    }

    private HubSettings saved() {
        ArgumentCaptor<HubSettings> captor = ArgumentCaptor.forClass(HubSettings.class);
        verify(hub).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void anUnknownCommandIsNotFound() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "nope", DataObject.empty()));

        assertEquals(404, thrown.status);
    }

    @Test
    void aGroupAndARoleCanBeChosenAsWhoMayUseACommand() {
        when(guild.getRoleById(9L)).thenReturn(mock(Role.class));

        api.save(guild, actor, "signup", DataObject.fromJson("{\"customAccess\": true, \"allowedRefs\": [\"group:cweb\", \"role:9\", \"group:cweb\"]}"));

        HubSettings s = saved();
        assertTrue(s.customAccess());
        assertEquals(List.of("group:cweb", "role:9"), s.allowedRefs(), "listed once each");
    }

    @Test
    void aGroupThatDoesNotExistOrARoleThatIsGoneIsRefused() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "signup",
                DataObject.fromJson("{\"customAccess\": true, \"allowedRefs\": [\"group:ghost\", \"role:404\", \"everyone\"]}")));

        assertEquals(3, thrown.problems.size());
        verify(hub, never()).save(any());
    }

    @Test
    void onlyAnAdminCanChangeWhoMayUseACommand() {
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "signup", DataObject.fromJson("{\"customAccess\": true}")));

        assertEquals(403, thrown.status);
        verify(hub, never()).save(any());
    }

    @Test
    void theDeveloperTierCanStillChangeWhereAndWhetherACommandWorks() {
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);
        channel(60L, true);

        api.save(guild, actor, "signup", DataObject.fromJson("{\"enabled\": false, \"channelIds\": [\"60\"], \"customAccess\": false}"));

        HubSettings s = saved();
        assertEquals(false, s.enabled());
        assertEquals(List.of(60L), s.channelIds());
    }

    @Test
    void aSettingLeftOutOfASaveKeepsItsCurrentValue() {
        when(hub.get(GUILD, HubCommand.SIGNUP)).thenReturn(new HubSettings(GUILD, "signup", true, true, List.of("group:cweb"), List.of(60L), "{}"));
        channel(60L, true);

        api.save(guild, actor, "signup", DataObject.fromJson("{\"enabled\": false}"));

        HubSettings s = saved();
        assertEquals(List.of("group:cweb"), s.allowedRefs());
        assertEquals(List.of(60L), s.channelIds());
    }

    @Test
    void aChannelThatIsGoneIsRefused() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "signup", DataObject.fromJson("{\"channelIds\": [\"70\"]}")));

        assertTrue(thrown.problems.getFirst().contains("doesn't exist"));
    }

    @Test
    void fixingTheSignupAdminChannelNeedsAChannelThatJonnyBotCanPostIn() {
        channel(77L, false);

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "signup",
                DataObject.fromJson("{\"signup\": {\"adminChannelId\": \"77\", \"lockAdminChannel\": true, \"publicChannelIds\": []}}")));

        assertTrue(thrown.problems.getFirst().contains("can't post"));
    }

    @Test
    void signupsCannotUseAForumThreadAsTheirAdminChannel() {
        ThreadChannel thread = mock(ThreadChannel.class);
        when(thread.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, 90L)).thenReturn(thread);

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "signup",
                DataObject.fromJson("{\"signup\": {\"adminChannelId\": \"90\", \"lockAdminChannel\": false, \"publicChannelIds\": []}}")));

        assertTrue(thrown.problems.getFirst().contains("text channel"));
    }

    @Test
    void aForumThreadIsFineWhereMembersMayUseACommand() {
        ThreadChannel thread = mock(ThreadChannel.class);
        when(guild.getChannelById(GuildMessageChannel.class, 91L)).thenReturn(thread);

        api.save(guild, actor, "signup", DataObject.fromJson("{\"channelIds\": [\"91\"]}"));

        assertEquals(List.of(91L), saved().channelIds());
    }

    @Test
    void theSignupSettingsAreStoredAsJsonAlongsideTheGenericOnes() {
        channel(77L, true);
        channel(80L, true);

        api.save(guild, actor, "signup", DataObject.fromJson("{\"signup\": {\"adminChannelId\": \"77\", \"lockAdminChannel\": true, \"publicChannelIds\": [\"80\"]}}"));

        assertEquals(HubService.writeSignup(new HubService.SignupPolicy(77L, true, List.of(80L))), saved().extras());
    }
}
