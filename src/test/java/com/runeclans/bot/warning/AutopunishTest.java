package com.runeclans.bot.warning;

import com.runeclans.bot.warning.Autopunish.Action;
import com.runeclans.bot.warning.Autopunish.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopunishTest {
    private final List<Rule> rules = List.of(new Rule(3, Action.TIMEOUT, 60), new Rule(5, Action.KICK, 0), new Rule(8, Action.BAN, 0));

    @Test
    void aRuleFiresOnlyAtExactlyItsCount() {
        assertEquals(Action.TIMEOUT, Autopunish.forCount(rules, 3).orElseThrow().action());
        assertTrue(Autopunish.forCount(rules, 4).isEmpty(), "a fourth warning does not repeat the third's timeout");
        assertEquals(Action.KICK, Autopunish.forCount(rules, 5).orElseThrow().action());
        assertTrue(Autopunish.forCount(rules, 1).isEmpty());
    }

    @Test
    void actionsAreDescribedInPlainWords() {
        assertEquals("timed out for 1 hour", new Rule(3, Action.TIMEOUT, 60).describe());
        assertEquals("timed out for 2 days", new Rule(3, Action.TIMEOUT, 2880).describe());
        assertEquals("timed out for 90 minutes", new Rule(3, Action.TIMEOUT, 90).describe());
        assertEquals("kicked", new Rule(3, Action.KICK, 0).describe());
        assertEquals("banned", new Rule(3, Action.BAN, 0).describe());
    }

    @Test
    void goodRulesHaveNoProblems() {
        assertTrue(Autopunish.problems(rules).isEmpty());
        assertTrue(Autopunish.problems(List.of()).isEmpty());
    }

    @Test
    void thresholdsMustBeInRangeAndUnique() {
        assertEquals(1, Autopunish.problems(List.of(new Rule(0, Action.KICK, 0))).size());
        assertEquals(1, Autopunish.problems(List.of(new Rule(Autopunish.MAX_THRESHOLD + 1, Action.KICK, 0))).size());
        assertEquals(1, Autopunish.problems(List.of(new Rule(3, Action.KICK, 0), new Rule(3, Action.BAN, 0))).size());
    }

    @Test
    void aTimeoutNeedsASensibleLength() {
        assertEquals(1, Autopunish.problems(List.of(new Rule(3, Action.TIMEOUT, 0))).size());
        assertEquals(1, Autopunish.problems(List.of(new Rule(3, Action.TIMEOUT, Autopunish.MAX_TIMEOUT_MINUTES + 1))).size());
        assertTrue(Autopunish.problems(List.of(new Rule(3, Action.TIMEOUT, Autopunish.MAX_TIMEOUT_MINUTES))).isEmpty());
        assertTrue(Autopunish.problems(List.of(new Rule(3, Action.KICK, 0))).isEmpty(), "length is ignored for a kick");
    }

    @Test
    void thereIsALimitOnHowManyRules() {
        List<Rule> many = new ArrayList<>();
        for (int i = 1; i <= Autopunish.MAX_RULES + 1; i++) many.add(new Rule(i, Action.KICK, 0));

        assertEquals(1, Autopunish.problems(many).size());
    }
}
