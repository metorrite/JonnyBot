package com.runeclans.bot;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsRepository;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.permission.OwnerOverride;
import com.runeclans.bot.permission.PermissionGroupService;
import com.runeclans.bot.retention.GuildRetentionRepository;
import com.runeclans.bot.retention.GuildRetentionService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A brand-new server's first minutes, with no real Discord: the bot is added, nothing is posted anywhere, the server inherits none of the home
 * server's settings, the dashboard opens only for someone who runs that server, and leaving starts the retention clock (and coming back stops it).
 * See docs/MULTI-SERVER-READINESS.md, gap G3.
 */
class SecondServerSmokeTest {
    private static final long HOME = 1_000L;
    private static final long NEW = 2_000L;

    /** The only things the join handler may ask a server: who it is. Any other call (a channel, a send, a role) fails the test. */
    private static final Set<String> ALLOWED_ON_JOIN = Set.of("getName", "getId", "getIdLong", "getMemberCount", "toString", "hashCode", "equals");

    private BotConfig config;
    private GuildRetentionRepository retentionRepository;
    private GuildLifecycleListener listener;
    private Guild newGuild;

    @BeforeEach
    void setUp() {
        config = mock(BotConfig.class);
        when(config.getGuildId()).thenReturn(HOME);
        when(config.getClanName()).thenReturn("Home Clan");
        when(config.getAdminRoleId()).thenReturn(11L);
        when(config.getRenameAlertChannelId()).thenReturn(22L);
        when(config.getVerificationReviewChannelId()).thenReturn(33L);
        when(config.getVerifiedClanRoleId()).thenReturn(44L);
        when(config.getVerifiedNonClanRoleId()).thenReturn(55L);
        when(config.getUnverifiedRoleId()).thenReturn(66L);

        retentionRepository = mock(GuildRetentionRepository.class);
        listener = new GuildLifecycleListener(new GuildRetentionService(retentionRepository, config, mock(com.runeclans.bot.starboard.StarboardRepository.class),
                mock(com.runeclans.bot.rolepanel.RolePanelRepository.class)));

        newGuild = mock(Guild.class, (InvocationOnMock call) -> {
            String method = call.getMethod().getName();
            if (!ALLOWED_ON_JOIN.contains(method)) throw new AssertionError("Joining a server must not touch it, but the bot called Guild." + method + "()");
            return switch (method) {
                case "getName" -> "New Server";
                case "getId" -> Long.toString(NEW);
                case "getIdLong" -> NEW;
                case "getMemberCount" -> 5;
                default -> call.callRealMethod();
            };
        });
    }

    @Test
    void joiningAServerPostsNothingAndOnlyStopsTheRetentionClock() {
        GuildJoinEvent join = mock(GuildJoinEvent.class);
        when(join.getGuild()).thenReturn(newGuild);

        listener.onGuildJoin(join); // would throw if it sent, queued, created or looked up anything in the server

        verify(retentionRepository).cancelDeparture(NEW);
    }

    @Test
    void leavingStartsTheRetentionClock() {
        GuildLeaveEvent leave = mock(GuildLeaveEvent.class);
        when(leave.getGuild()).thenReturn(newGuild);

        listener.onGuildLeave(leave);

        verify(retentionRepository).recordDeparture(NEW);
    }

    @Test
    void aNewServerInheritsNothingFromTheHomeServer() {
        GuildSettingsRepository stored = mock(GuildSettingsRepository.class);
        when(stored.get(anyLong())).thenReturn(null);
        GuildSettingsService settings = new GuildSettingsService(stored, config);

        GuildSettings fresh = settings.getEffective(NEW);

        assertNull(fresh.clanName(), "the home clan must not leak");
        assertNull(fresh.adminRoleId());
        assertNull(fresh.renameAlertChannelId());
        assertNull(fresh.verificationReviewChannelId());
        assertNull(fresh.verifiedClanRoleId());
        assertNull(fresh.verifiedNonClanRoleId());
        assertNull(fresh.unverifiedRoleId());
        assertNull(fresh.onboardingRoleId());
        assertNull(fresh.supportRoleId());
        assertNull(fresh.developerRoleId());

        GuildSettings home = settings.getEffective(HOME);
        assertEquals("Home Clan", home.clanName(), "the home server keeps its environment defaults");
        assertEquals(11L, home.adminRoleId());
    }

    @Test
    void theDashboardOpensForWhoeverRunsTheNewServerAndRefusesEveryoneElse() {
        AdminRoleFilter adminFilter = mock(AdminRoleFilter.class);
        PermissionGroupService groups = mock(PermissionGroupService.class);
        DashboardAccess access = new DashboardAccess(adminFilter, groups, config, new OwnerOverride(mock(com.runeclans.bot.notice.BotOwners.class)));
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(NEW);

        Member stranger = mock(Member.class);
        when(adminFilter.isAuthorized(any(), any())).thenReturn(false);
        when(groups.isMember(any(), any(), anyString())).thenReturn(false);
        assertEquals(DashboardAccess.Tier.NONE, access.tierOf(guild, stranger), "an ordinary member is refused");

        Member manager = mock(Member.class);
        when(manager.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        assertEquals(DashboardAccess.Tier.ADMIN, access.tierOf(guild, manager), "someone with Manage Server can set up their own server");

        Guild home = mock(Guild.class);
        when(home.getIdLong()).thenReturn(HOME);
        assertEquals(DashboardAccess.Tier.NONE, access.tierOf(home, manager), "Manage Server alone opens nothing in the home server");
        assertFalse(access.tierOf(home, stranger) == DashboardAccess.Tier.ADMIN);
    }
}
