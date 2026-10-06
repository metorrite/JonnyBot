package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollRulesTest {
    @Test
    void aSensiblePollPasses() {
        assertNull(PollRules.validate("Best boss?", List.of("Telos", "Vorago", "Nex")));
    }

    @Test
    void theQuestionAndOptionCountsAreChecked() {
        assertNotNull(PollRules.validate("  ", List.of("a", "b")));
        assertNotNull(PollRules.validate("x".repeat(151), List.of("a", "b")));
        assertNotNull(PollRules.validate("Q", List.of("only")));
        assertNotNull(PollRules.validate("Q", List.of("1", "2", "3", "4", "5", "6", "7")));
        assertNull(PollRules.validate("Q", List.of("1", "2", "3", "4", "5", "6")));
    }

    @Test
    void duplicatesIgnoreCaseAndLongOptionsAreRefused() {
        assertTrue(PollRules.validate("Q", List.of("Yes", "yes")).contains("twice"));
        assertNotNull(PollRules.validate("Q", List.of("a", "x".repeat(101))));
    }

    @Test
    void durationsAreBounded() {
        assertNull(PollRules.validateDuration(null));
        assertNull(PollRules.validateDuration(24));
        assertNotNull(PollRules.validateDuration(0));
        assertNotNull(PollRules.validateDuration(24 * 31));
    }
}
