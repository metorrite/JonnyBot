package com.younglings.bot.permission;

import com.younglings.bot.permission.DashboardAccess.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
