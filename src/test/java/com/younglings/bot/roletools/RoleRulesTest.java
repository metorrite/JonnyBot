package com.younglings.bot.roletools;

import com.younglings.bot.roletools.RoleRules.Holder;
import com.younglings.bot.roletools.RoleRules.Mode;
import com.younglings.bot.roletools.RoleRules.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleRulesTest {
    private static final List<Holder> HOLDERS = List.of(new Holder(1, true, false), new Holder(2, true, true), new Holder(3, false, true), new Holder(4, false, false));

    @Test
    void addingChangesOnlySourceHoldersWhoLackTheTarget() {
        assertEquals(List.of(1L), RoleRules.affected(Mode.ADD, HOLDERS));
    }

    @Test
    void removingChangesOnlySourceHoldersWhoHaveTheTarget() {
        assertEquals(List.of(2L), RoleRules.affected(Mode.REMOVE, HOLDERS));
    }

    @Test
    void aRoleTheClanSyncOwnsOrTheBotCannotHandOutIsRefused() {
        assertNull(RoleRules.roleRefusal(true, false, false, true, "Raiders"));
        assertTrue(RoleRules.roleRefusal(true, false, true, true, "Member").contains("clan role sync"));
        assertNotNull(RoleRules.roleRefusal(true, false, false, false, "Admin"));
        assertNotNull(RoleRules.roleRefusal(true, true, false, true, "everyone"));
        assertNotNull(RoleRules.roleRefusal(false, false, false, false, ""));
    }

    @Test
    void anAdminMayUseAnyRoleAndAnyoneElseOnlyTheirListed() {
        assertTrue(RoleRules.mayUse(true, Set.of(), 5));
        assertTrue(RoleRules.mayUse(false, Set.of(5L), 5));
        assertFalse(RoleRules.mayUse(false, Set.of(6L), 5));
        assertFalse(RoleRules.mayUse(false, Set.of(), 5));
    }

    @Test
    void twoRolesMustDiffer() {
        assertNotNull(RoleRules.bulkRefusal(5, 5));
        assertNull(RoleRules.bulkRefusal(5, 6));
    }

    @Test
    void aJobOnlyMovesAlongTheAllowedStepsAndNeverStartsFromDoneOrCancelled() {
        assertTrue(RoleRules.canMove(Status.PLANNED, Status.RUNNING));
        assertTrue(RoleRules.canMove(Status.PAUSED, Status.RUNNING));
        assertTrue(RoleRules.canMove(Status.RUNNING, Status.PAUSED));
        assertFalse(RoleRules.canMove(Status.DONE, Status.RUNNING));
        assertFalse(RoleRules.canMove(Status.CANCELLED, Status.RUNNING));
        assertFalse(RoleRules.canMove(Status.PLANNED, Status.PAUSED));
        assertFalse(RoleRules.canMove(Status.DONE, Status.CANCELLED));
        assertFalse(RoleRules.canMove(Status.RUNNING, Status.PLANNED));
    }
}
