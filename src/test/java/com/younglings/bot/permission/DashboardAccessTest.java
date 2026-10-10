package com.younglings.bot.permission;

import com.younglings.bot.permission.DashboardAccess.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardAccessTest {
    @Test
    void adminTierAlwaysGetsIn() {
        assertEquals(Tier.ADMIN, DashboardAccess.tierOf(true, null, List.of()));
        assertEquals(Tier.ADMIN, DashboardAccess.tierOf(true, 5L, List.of(5L)));
    }

    @Test
    void theDeveloperRoleGetsInWithoutBeingAdmin() {
        assertEquals(Tier.DEVELOPER, DashboardAccess.tierOf(false, 5L, List.of(1L, 5L)));
    }

    @Test
    void everyoneElseIsRefused() {
        assertEquals(Tier.NONE, DashboardAccess.tierOf(false, 5L, List.of(1L, 2L)));
        assertEquals(Tier.NONE, DashboardAccess.tierOf(false, 5L, List.of()));
    }

    @Test
    void withNoDeveloperRoleConfiguredOnlyAdminsGetIn() {
        assertEquals(Tier.NONE, DashboardAccess.tierOf(false, null, List.of(5L)));
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
}
