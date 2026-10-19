package com.younglings.bot.rolekeep;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleKeepRulesTest {
    @Test
    void lengthsOfTimeAreReadInTheUsualUnits() {
        assertEquals(Optional.of(Duration.ofMinutes(30)), RoleKeepRules.parseDuration("30m"));
        assertEquals(Optional.of(Duration.ofHours(2)), RoleKeepRules.parseDuration(" 2H "));
        assertEquals(Optional.of(Duration.ofDays(7)), RoleKeepRules.parseDuration("1w"));
        assertEquals(Optional.of(Duration.ofHours(36)), RoleKeepRules.parseDuration("1d12h"));
    }

    @Test
    void nonsenseAndOutOfRangeLengthsAreRefused() {
        for (String bad : new String[]{"", "  ", "abc", "10", "5x", "30s", "2h soon", "1d 2", "9999d", "400d", "0m", null}) {
            assertTrue(RoleKeepRules.parseDuration(bad).isEmpty(), "[" + bad + "]");
        }
    }

    @Test
    void onLeavingOnlyStickyRolesThatAreStillSafeAreRemembered() {
        // role 3 is on the sticky list but has since become a clan-sync role, 4 is not sticky
        List<Long> stored = RoleKeepRules.rolesToStore(Set.of(1L, 3L, 4L), Set.of(1L, 3L), id -> id != 3);

        assertEquals(List.of(1L), stored);
    }

    @Test
    void onReturningEverythingRememberedAndTimedIsGivenBackExceptWhatTheyHoldOrIsNoLongerSafe() {
        List<Long> give = RoleKeepRules.toReapply(Set.of(1L, 2L, 3L), Set.of(5L), Set.of(2L), id -> id != 3);

        assertEquals(List.of(1L, 5L), give);
    }

    @Test
    void givingBackIsIdempotentSoARepeatedJoinCannotLoop() {
        Set<Long> held = new java.util.HashSet<>(Set.of(2L));
        List<Long> first = RoleKeepRules.toReapply(Set.of(1L), Set.of(), held, id -> true);
        held.addAll(first);

        assertEquals(List.of(1L), first);
        assertTrue(RoleKeepRules.toReapply(Set.of(1L), Set.of(), held, id -> true).isEmpty());
    }

    @Test
    void theClanSyncStaffAndUnsafeRolesAreAllRefused() {
        assertNull(RoleKeepRules.roleRefusal(true, false, false, false, true, "Muted"));
        assertTrue(RoleKeepRules.roleRefusal(true, false, true, false, true, "Member").contains("always wins"));
        assertNotNull(RoleKeepRules.roleRefusal(true, false, false, true, true, "Admin"));
        assertNotNull(RoleKeepRules.roleRefusal(true, false, false, false, false, "Boss"));
        assertNotNull(RoleKeepRules.roleRefusal(true, true, false, false, true, "everyone"));
        assertNotNull(RoleKeepRules.roleRefusal(false, false, false, false, false, ""));
    }
}
