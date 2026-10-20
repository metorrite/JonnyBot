package com.younglings.bot.points;

import com.younglings.bot.points.PointsExtrasRules.RolePlan;
import com.younglings.bot.points.PointsExtrasRules.RoleReward;
import com.younglings.bot.points.PointsExtrasRules.Trigger;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsExtrasRulesTest {
    private static RoleReward reward(long id, long threshold, long role, boolean removeLower) {
        return new RoleReward(id, 1, threshold, role, removeLower);
    }

    private static final List<RoleReward> LADDER = List.of(reward(1, 100, 11, false), reward(2, 500, 12, true), reward(3, 1000, 13, true));

    // ---------- role rewards

    @Test
    void reachingAThresholdGivesItsRole() {
        RolePlan plan = PointsExtrasRules.planRoles(LADDER, 100, Set.of(), Set.of());
        assertEquals(Set.of(11L), plan.addRoles());
        assertTrue(plan.removeRoles().isEmpty());
        assertEquals(List.of(1L), plan.granted().stream().map(RoleReward::id).toList());
    }

    @Test
    void belowEveryThresholdNothingHappens() {
        assertTrue(PointsExtrasRules.planRoles(LADDER, 99, Set.of(), Set.of()).none());
        assertTrue(PointsExtrasRules.planRoles(List.of(), 5000, Set.of(), Set.of()).none());
    }

    @Test
    void aRewardAlreadyGivenIsNeverGivenAgainSoARoleStaffTookAwayStaysGone() {
        RolePlan plan = PointsExtrasRules.planRoles(LADDER, 150, Set.of(1L), Set.of());
        assertTrue(plan.none(), "reward 1 was given before; the member lost the role on purpose");
        assertTrue(plan.addRoles().isEmpty());
    }

    @Test
    void aRoleTheMemberAlreadyHasIsNotAddedButTheRewardIsStillCountedAsGiven() {
        RolePlan plan = PointsExtrasRules.planRoles(LADDER, 100, Set.of(), Set.of(11L));
        assertTrue(plan.addRoles().isEmpty());
        assertEquals(1, plan.granted().size());
    }

    @Test
    void removeLowerSwapsTheLowerRolesOutWhenTheHigherOneIsGiven() {
        RolePlan plan = PointsExtrasRules.planRoles(LADDER, 500, Set.of(1L), Set.of(11L));
        assertEquals(Set.of(12L), plan.addRoles());
        assertEquals(Set.of(11L), plan.removeRoles());
    }

    @Test
    void jumpingSeveralStepsAtOnceEndsWithOnlyTheHighestRoleWhenEachRemovesTheLower() {
        // a donation takes the member from nothing to 1000 in one go
        RolePlan plan = PointsExtrasRules.planRoles(LADDER, 1000, Set.of(), Set.of());
        assertEquals(Set.of(13L), plan.addRoles(), "rewards 2 and 3 both remove the lower roles, so only the top role is given");
        assertEquals(3, plan.granted().size(), "all three count as given");
        assertTrue(plan.removeRoles().isEmpty(), "they held none of the lower roles");
    }

    @Test
    void jumpingSeveralStepsKeepsLowerRolesThatAreNotRemovedByTheHigherOnes() {
        List<RoleReward> keepLower = List.of(reward(1, 100, 11, false), reward(2, 500, 12, false));
        RolePlan plan = PointsExtrasRules.planRoles(keepLower, 600, Set.of(), Set.of());
        assertEquals(Set.of(11L, 12L), plan.addRoles());
    }

    @Test
    void aBalanceThatFallsLaterNeverTakesARoleBack() {
        // the member earned reward 2 and then spent points on a prize: balance 40, rewards 1 and 2 recorded as given
        RolePlan plan = PointsExtrasRules.planRoles(LADDER, 40, Set.of(1L, 2L), Set.of(12L));
        assertTrue(plan.none());
        assertTrue(plan.removeRoles().isEmpty());
    }

    @Test
    void evaluatingAgainAfterApplyingChangesNothing() {
        RolePlan first = PointsExtrasRules.planRoles(LADDER, 500, Set.of(), Set.of());
        Set<Long> granted = new java.util.HashSet<>();
        first.granted().forEach(r -> granted.add(r.id()));
        assertTrue(PointsExtrasRules.planRoles(LADDER, 500, granted, first.addRoles()).none());
    }

    @Test
    void roleRewardProblemsAreCaught() {
        assertFalse(PointsExtrasRules.roleRewardProblems(0, 5, List.of()).isEmpty());
        assertFalse(PointsExtrasRules.roleRewardProblems(PointsExtrasRules.MAX_THRESHOLD + 1, 5, List.of()).isEmpty());
        assertFalse(PointsExtrasRules.roleRewardProblems(100, 5, LADDER).isEmpty(), "threshold already used");
        assertFalse(PointsExtrasRules.roleRewardProblems(200, 11, LADDER).isEmpty(), "role already used");
        assertTrue(PointsExtrasRules.roleRewardProblems(200, 99, LADDER).isEmpty());
        List<RoleReward> full = java.util.stream.IntStream.range(0, PointsExtrasRules.MAX_ROLE_REWARDS).mapToObj(i -> reward(i + 1, i + 1, 100 + i, false)).toList();
        assertFalse(PointsExtrasRules.roleRewardProblems(9999, 9999, full).isEmpty());
    }

    // ---------- keyword triggers

    @Test
    void aKeywordMatchesAsAWholeWordOrPhraseIgnoringCase() {
        assertTrue(PointsExtrasRules.matches("thanks", "Thanks @Zezima!"));
        assertTrue(PointsExtrasRules.matches("thank you", "ok THANK YOU so much"));
        assertTrue(PointsExtrasRules.matches("thanks", "thanks"));
        assertFalse(PointsExtrasRules.matches("thanks", "thanksgiving was fun"));
        assertFalse(PointsExtrasRules.matches("thanks", "nothanks"));
        assertFalse(PointsExtrasRules.matches("", "anything"));
    }

    @Test
    void onlyRealMentionedMembersOtherThanTheWriterAreCountedUpToThree() {
        assertEquals(List.of(2L, 3L), PointsExtrasRules.receivers(1, List.of(1L, 2L, 3L, 2L), Set.of()));
        assertEquals(List.of(3L), PointsExtrasRules.receivers(1, List.of(2L, 3L), Set.of(2L)), "bots are never paid");
        assertEquals(List.of(2L, 3L, 4L), PointsExtrasRules.receivers(1, List.of(2L, 3L, 4L, 5L, 6L), Set.of()), "a crowd mention pays three at most");
        assertTrue(PointsExtrasRules.receivers(1, List.of(1L), Set.of()).isEmpty(), "nobody pays themselves");
        assertTrue(PointsExtrasRules.receivers(1, List.of(), Set.of()).isEmpty());
    }

    @Test
    void thePaymentKeyIsOnePerWriterReceiverTriggerAndDay() {
        LocalDate day = LocalDate.of(2026, 10, 19);
        assertEquals("kw:7:1:2:2026-10-19", PointsExtrasRules.key(7, 1, 2, day));
        assertEquals("kw:7:%:2:2026-10-19", PointsExtrasRules.receivedPattern(7, 2, day));
        assertFalse(PointsExtrasRules.key(7, 1, 2, day).equals(PointsExtrasRules.key(7, 1, 2, day.plusDays(1))));
        assertFalse(PointsExtrasRules.key(7, 1, 2, day).equals(PointsExtrasRules.key(7, 3, 2, day)), "a different writer is a different payment");
    }

    @Test
    void theCooldownBlocksTheSameWriterAndTriggerButNotOthers() {
        PointsExtrasRules.Cooldowns c = new PointsExtrasRules.Cooldowns();
        assertTrue(c.tryUse(1_000, 1, 7, 60));
        assertFalse(c.tryUse(30_000, 1, 7, 60), "thirty seconds later");
        assertTrue(c.tryUse(30_000, 2, 7, 60), "another writer");
        assertTrue(c.tryUse(30_000, 1, 8, 60), "another trigger");
        assertTrue(c.tryUse(61_001, 1, 7, 60), "after the cool-down");
        assertTrue(c.tryUse(1_000_000, 1, 9, 0), "a zero cool-down never blocks");
        assertTrue(c.tryUse(1_000_000, 1, 9, 0));
    }

    @Test
    void triggerProblemsAreCaught() {
        List<Trigger> none = List.of();
        assertTrue(PointsExtrasRules.triggerProblems("thanks", 1, 60, 5, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("x", 1, 60, 5, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("bad <word>", 1, 60, 5, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("thanks", 0, 60, 5, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("thanks", PointsExtrasRules.MAX_TRIGGER_POINTS + 1, 60, 5, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("thanks", 1, -1, 5, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("thanks", 1, 60, 0, none).isEmpty());
        assertFalse(PointsExtrasRules.triggerProblems("Thanks ", 1, 60, 5, List.of(new Trigger(1, 1, "thanks", 1, 60, 5, true))).isEmpty(), "same word, any case");
        List<Trigger> full = java.util.stream.IntStream.range(0, PointsExtrasRules.MAX_TRIGGERS).mapToObj(i -> new Trigger(i + 1, 1, "word" + i, 1, 60, 5, true)).toList();
        assertFalse(PointsExtrasRules.triggerProblems("another", 1, 60, 5, full).isEmpty());
    }

    @Test
    void aKeywordIsCleanedBeforeItIsChecked() {
        assertEquals("thank you", PointsExtrasRules.cleanKeyword("  Thank   YOU "));
    }
}
