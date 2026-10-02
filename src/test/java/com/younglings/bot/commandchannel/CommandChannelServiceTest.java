package com.younglings.bot.commandchannel;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.LongToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandChannelServiceTest {
    // Role ids -> hierarchy positions. 99 (and anything else) doesn't exist -> -1, like a deleted role.
    private static final long GUEST = 1, MEMBER = 2, MOD = 3, ADMIN = 4, EVENTS = 5, DELETED = 99;
    private static final LongToIntFunction POSITION = id -> switch ((int) id) {
        case 1 -> 10;
        case 2 -> 20;
        case 5 -> 30;
        case 3 -> 50;
        case 4 -> 80;
        default -> -1;
    };

    private static CommandChannelGroup group(Long applyBelow, Long exemptFrom, Set<Long> applyRoles, Set<Long> exemptRoles) {
        return new CommandChannelGroup(1, 1, "G", null, true, applyBelow, exemptFrom, Set.of(10L), applyRoles, exemptRoles);
    }

    private static boolean covers(CommandChannelGroup group, int topPosition, Long... roles) {
        return CommandChannelService.appliesTo(group, Set.of(roles), topPosition, POSITION);
    }

    @Test
    void withNoRulesItCoversEveryone() {
        var g = group(null, null, Set.of(), Set.of());
        assertTrue(covers(g, 0));
        assertTrue(covers(g, 80, ADMIN));
    }

    @Test
    void anExemptRoleAlwaysWinsEvenOverAnApplyRole() {
        var g = group(null, null, Set.of(MEMBER), Set.of(EVENTS));
        assertTrue(covers(g, 20, MEMBER));
        assertFalse(covers(g, 30, MEMBER, EVENTS));
    }

    @Test
    void exemptFromRankExemptsThatRankAndEverythingAbove() {
        var g = group(null, MOD, Set.of(), Set.of());
        assertTrue(covers(g, 20, MEMBER));
        assertFalse(covers(g, 50, MOD));   // exactly at the rank counts as exempt
        assertFalse(covers(g, 80, ADMIN));
    }

    @Test
    void applyBelowRankCoversOnlyStrictlyLowerRoles() {
        var g = group(MOD, null, Set.of(), Set.of());
        assertTrue(covers(g, 20, MEMBER));
        assertFalse(covers(g, 50, MOD));   // exactly at the rank is NOT below it
        assertFalse(covers(g, 80, ADMIN));
    }

    @Test
    void applySpecificRolesCoversOnlyThoseHoldingOne() {
        var g = group(null, null, Set.of(MEMBER), Set.of());
        assertTrue(covers(g, 20, MEMBER));
        assertFalse(covers(g, 10, GUEST));
        assertFalse(covers(g, 0));
    }

    @Test
    void theTwoApplyRulesCombineAsEitherOr() {
        var g = group(MEMBER, null, Set.of(EVENTS), Set.of());
        assertTrue(covers(g, 10, GUEST));          // below Member
        assertTrue(covers(g, 30, EVENTS));         // not below, but holds the apply role
        assertFalse(covers(g, 30));                // neither
    }

    @Test
    void exemptFromRankBeatsApplyBelow() {
        var g = group(ADMIN, MOD, Set.of(), Set.of());
        assertTrue(covers(g, 40));
        assertFalse(covers(g, 60));                // below Admin, but at/above the Mod exemption
    }

    @Test
    void anExemptRoleBeatsApplyBelow() {
        var g = group(ADMIN, null, Set.of(), Set.of(MEMBER));
        assertFalse(covers(g, 10, MEMBER));
        assertTrue(covers(g, 10, GUEST));
    }

    @Test
    void aDeletedRankRolePausesTheGroupInsteadOfGuessing() {
        assertFalse(covers(group(DELETED, null, Set.of(), Set.of()), 0));
        assertFalse(covers(group(null, DELETED, Set.of(), Set.of()), 0));
        assertFalse(covers(group(MOD, DELETED, Set.of(), Set.of()), 0), "one good rank role doesn't rescue a broken one");
    }

    @Test
    void aDeletedSpecificRoleIsHarmless() {
        var applyOnlyDeleted = group(null, null, Set.of(DELETED), Set.of());
        assertFalse(covers(applyOnlyDeleted, 0, MEMBER), "nobody can hold it, so the apply list matches no one");

        var exemptOnlyDeleted = group(null, null, Set.of(), Set.of(DELETED));
        assertTrue(covers(exemptOnlyDeleted, 0), "an exempt role nobody holds exempts nobody");
    }

    @Test
    void messageFallsBackToTheBuiltInDefault() {
        assertEquals(CommandChannelService.DEFAULT_MESSAGE, CommandChannelService.messageFor(withMessage(null)));
        assertEquals(CommandChannelService.DEFAULT_MESSAGE, CommandChannelService.messageFor(withMessage("   ")));
        assertEquals("Use #bot-commands!", CommandChannelService.messageFor(withMessage("Use #bot-commands!")));
    }

    @Test
    void theFixedGroupsAreRecognizedRegardlessOfCase() {
        assertTrue(CommandChannelService.isProtected(named("default")));
        assertTrue(CommandChannelService.isProtected(named("CUSTOM")));
        assertFalse(CommandChannelService.isProtected(named("Staff")));
        assertTrue(CommandChannelService.isDefaultGroup(named("DEFAULT")));
        assertFalse(CommandChannelService.isDefaultGroup(named("Custom")));
    }

    private static CommandChannelGroup withMessage(String message) {
        return new CommandChannelGroup(1, 1, "G", message, true, null, null, Set.of(), Set.of(), Set.of());
    }

    private static CommandChannelGroup named(String name) {
        return new CommandChannelGroup(1, 1, name, null, true, null, null, Set.of(), Set.of(), Set.of());
    }
}
