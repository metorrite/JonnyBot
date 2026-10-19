package com.younglings.bot.rolerules;

import com.younglings.bot.rolerules.RoleRuleRules.Change;
import com.younglings.bot.rolerules.RoleRuleRules.Event;
import com.younglings.bot.rolerules.RoleRuleRules.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleRuleRulesTest {
    private static Rule rule(long id, Event event, List<Long> when, List<Long> add, List<Long> remove) {
        return new Rule(id, 1, "r" + id, event, when, add, remove, true);
    }

    @Test
    void aGainsRuleFiresOnlyOnTheChangeThatCompletesItsConditions() {
        Rule r = rule(1, Event.GAINS, List.of(10L, 11L), List.of(20L), List.of());

        assertFalse(RoleRuleRules.fires(r, Set.of(10L), Set.of(10L), true), "only one of the two roles so far");
        assertTrue(RoleRuleRules.fires(r, Set.of(10L, 11L), Set.of(11L), true), "the second role completes it");
        assertFalse(RoleRuleRules.fires(r, Set.of(10L, 11L, 99L), Set.of(99L), true), "an unrelated role does not re-fire it");
        assertFalse(RoleRuleRules.fires(r, Set.of(10L, 11L), Set.of(11L), false), "losing a role never fires a gains rule");
    }

    @Test
    void aLosesRuleFiresOnlyWhenTheConditionsStopBeingTrue() {
        Rule r = rule(1, Event.LOSES, List.of(10L, 11L), List.of(), List.of(20L));

        assertTrue(RoleRuleRules.fires(r, Set.of(10L), Set.of(11L), false), "had both, lost one");
        assertFalse(RoleRuleRules.fires(r, Set.of(), Set.of(12L), false), "an unrelated role was lost");
        assertFalse(RoleRuleRules.fires(r, Set.of(), Set.of(10L), false) && false);
        assertFalse(RoleRuleRules.fires(r, Set.of(10L), Set.of(11L), true), "gaining a role never fires a loses rule");
    }

    @Test
    void aLosesRuleDoesNotFireForSomeoneWhoNeverHadAllTheConditions() {
        Rule r = rule(1, Event.LOSES, List.of(10L, 11L), List.of(), List.of(20L));

        // they held only 10, and lose 10: before the change they did not hold both, so the condition did not stop being true
        assertFalse(RoleRuleRules.fires(r, Set.of(), Set.of(10L), false));
    }

    @Test
    void aDisabledRuleNeverFires() {
        Rule off = new Rule(1, 1, "off", Event.GAINS, List.of(10L), List.of(20L), List.of(), false);

        assertFalse(RoleRuleRules.fires(off, Set.of(10L), Set.of(10L), true));
    }

    @Test
    void pointlessChangesAreLeftOutAndAGiveBeatsATakeOfTheSameRole() {
        Rule a = rule(1, Event.GAINS, List.of(10L), List.of(20L, 21L), List.of(30L, 31L));
        Rule b = rule(2, Event.GAINS, List.of(10L), List.of(30L), List.of());

        Change change = RoleRuleRules.changesFor(List.of(a, b), Set.of(10L, 21L, 31L), Set.of(10L), true);

        assertEquals(Set.of(20L, 30L), change.add(), "21 is already held");
        assertEquals(Set.of(31L), change.remove(), "30 is not held, and it is being given by the other rule");
    }

    @Test
    void aRuleThatChangesItsOwnConditionIsRefused() {
        assertNotNull(RoleRuleRules.shapeProblem(rule(1, Event.GAINS, List.of(10L), List.of(10L), List.of())));
        assertNotNull(RoleRuleRules.shapeProblem(rule(1, Event.GAINS, List.of(10L), List.of(), List.of(10L))));
        assertNotNull(RoleRuleRules.shapeProblem(rule(1, Event.GAINS, List.of(10L), List.of(20L), List.of(20L))));
        assertNotNull(RoleRuleRules.shapeProblem(rule(1, Event.GAINS, List.of(), List.of(20L), List.of())));
        assertNotNull(RoleRuleRules.shapeProblem(rule(1, Event.GAINS, List.of(10L), List.of(), List.of())));
        assertNull(RoleRuleRules.shapeProblem(rule(1, Event.GAINS, List.of(10L), List.of(20L), List.of(30L))));
    }

    @Test
    void twoRulesThatLeadBackToEachOtherAreALoop() {
        Rule ab = rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of());
        Rule ba = rule(2, Event.GAINS, List.of(2L), List.of(1L), List.of());

        assertEquals(List.of(1L, 2L, 1L), RoleRuleRules.findCycle(List.of(ab, ba)));
    }

    @Test
    void aLoopBuiltFromAGiveAndATakeIsStillALoop() {
        Rule gives = rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of());
        Rule takes = rule(2, Event.GAINS, List.of(2L), List.of(), List.of(1L));

        assertFalse(RoleRuleRules.findCycle(List.of(gives, takes)).isEmpty());
    }

    @Test
    void aLongerLoopThroughThreeRulesIsFound() {
        Rule r1 = rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of());
        Rule r2 = rule(2, Event.GAINS, List.of(2L), List.of(3L), List.of());
        Rule r3 = rule(3, Event.LOSES, List.of(3L), List.of(1L), List.of());

        assertFalse(RoleRuleRules.findCycle(List.of(r1, r2, r3)).isEmpty());
    }

    @Test
    void aChainThatEndsIsNotALoopAndADisabledRuleDoesNotCount() {
        Rule r1 = rule(1, Event.GAINS, List.of(1L), List.of(2L), List.of());
        Rule r2 = rule(2, Event.GAINS, List.of(2L), List.of(3L), List.of());
        Rule backOff = new Rule(3, 1, "off", Event.GAINS, List.of(3L), List.of(1L), List.of(), false);

        assertTrue(RoleRuleRules.findCycle(List.of(r1, r2)).isEmpty());
        assertTrue(RoleRuleRules.findCycle(List.of(r1, r2, backOff)).isEmpty());
    }

    @Test
    void aChainIsCutAfterTheLimit() {
        assertTrue(RoleRuleRules.chainAllowed(0));
        assertTrue(RoleRuleRules.chainAllowed(RoleRuleRules.MAX_CHAIN_DEPTH - 1));
        assertFalse(RoleRuleRules.chainAllowed(RoleRuleRules.MAX_CHAIN_DEPTH));
    }
}
