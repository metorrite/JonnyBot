package com.runeclans.bot.permission;

import com.runeclans.bot.permission.DashboardAccess.Tier;
import com.runeclans.bot.config.BotConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardAccessTest {
    @Test
    void adminTierAlwaysGetsIn() {
        assertEquals(Tier.ADMIN, DashboardAccess.tierOf(true, false));
        assertEquals(Tier.ADMIN, DashboardAccess.tierOf(true, true));
    }

    @Test
    void theDeveloperGroupGetsInWithoutBeingAdmin() {
        assertEquals(Tier.DEVELOPER, DashboardAccess.tierOf(false, true));
    }

    @Test
    void everyoneElseIsRefused() {
        assertEquals(Tier.NONE, DashboardAccess.tierOf(false, false));
    }

    private static final long HOME = 1L, OTHER = 2L;

    @Test
    void inAnotherServerAnAdministratorOrServerManagerCanOpenTheDashboard() {
        assertTrue(DashboardAccess.managesOtherServer(HOME, OTHER, true, false));
        assertTrue(DashboardAccess.managesOtherServer(HOME, OTHER, false, true));
        assertFalse(DashboardAccess.managesOtherServer(HOME, OTHER, false, false), "an ordinary member does not");
    }

    @Test
    void theHomeServersRulesAreNotWidened() {
        assertFalse(DashboardAccess.managesOtherServer(HOME, HOME, true, true), "the home server still uses only its Admin role, owner and Developer role");
    }

    @Test
    void withNoHomeServerConfiguredNothingIsWidened() {
        assertFalse(DashboardAccess.managesOtherServer(null, OTHER, true, true));
    }

    @Test
    void anEventCoordinatorMayReachOnlyTheEventManagersRoutes() {
        assertTrue(DashboardAccess.coordinatorMayUse("GET", new String[]{"events"}));
        assertTrue(DashboardAccess.coordinatorMayUse("POST", new String[]{"events", "12", "players"}));
        assertTrue(DashboardAccess.coordinatorMayUse("GET", new String[]{"structure"}));
        for (String route : new String[]{"permissions", "welcome", "premium-guilds", "beta-guilds", "access-grants", "points", "tickets", "members", "audit", "settings", "hub", "image", "public-events"}) {
            assertFalse(DashboardAccess.coordinatorMayUse("GET", new String[]{route}), route + " is not for coordinators");
            assertFalse(DashboardAccess.coordinatorMayUse("PUT", new String[]{route, "1"}), route + " is not for coordinators");
        }
        assertFalse(DashboardAccess.coordinatorMayUse("PUT", new String[]{"structure"}), "structure is read only");
        assertFalse(DashboardAccess.coordinatorMayUse("GET", new String[0]));
    }

    @Test
    void aCoordinatorIsOnlyEverACoordinatorWhenTheyHaveNoTier() {
        AdminRoleFilter filter = mock(AdminRoleFilter.class);
        PermissionGroupService groups = mock(PermissionGroupService.class);
        BotConfig config = mock(BotConfig.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        when(guild.getIdLong()).thenReturn(HOME);
        when(config.getGuildId()).thenReturn(HOME);
        DashboardAccess access = new DashboardAccess(filter, groups, config, new OwnerOverride());

        when(groups.isMember(guild, member, PermissionGroup.EVENT_COORDINATOR)).thenReturn(true);
        assertTrue(access.isEventCoordinator(guild, member));
        assertEquals(Tier.NONE, access.tierOf(guild, member), "a coordinator has no tier, so tier-only code refuses them");

        when(filter.isAuthorized(guild, member)).thenReturn(true);
        assertFalse(access.isEventCoordinator(guild, member), "an Admin is not a coordinator: the tier already covers events");

        when(filter.isAuthorized(guild, member)).thenReturn(false);
        when(groups.isMember(guild, member, PermissionGroup.EVENT_COORDINATOR)).thenReturn(false);
        assertFalse(access.isEventCoordinator(guild, member));
    }
}
