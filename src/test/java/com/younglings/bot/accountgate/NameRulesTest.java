package com.younglings.bot.accountgate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameRulesTest {
    @Test
    void aWildcardMatchesAnyRunOfCharactersAndIgnoresCase() {
        assertTrue(NameRules.matches("free*nitro", "FreeDiscordNitro"));
        assertTrue(NameRules.matches("*spam*", "xx_SPAM_bot"));
        assertTrue(NameRules.matches("spam*", "spammer"));
        assertTrue(NameRules.matches("*spam", "big spam"));
    }

    @Test
    void aRuleWithoutAWildcardOnlyMatchesTheExactName() {
        assertTrue(NameRules.matches("badname", "BadName"));
        assertFalse(NameRules.matches("badname", "badname2"));
        assertFalse(NameRules.matches("badname", "xbadname"));
    }

    @Test
    void otherCharactersAreTakenLiterally() {
        assertFalse(NameRules.matches("a.b.c", "axbxc"));
        assertTrue(NameRules.matches("a.b.c", "a.b.c"));
        assertTrue(NameRules.matches("(x)*y+", "(x)zzy+"));
    }

    @Test
    void aRuleThatWouldCatchEveryoneIsRefused() {
        assertNotNull(NameRules.problem("*"));
        assertNotNull(NameRules.problem("**"));
        assertNotNull(NameRules.problem("*a*"));
        assertNotNull(NameRules.problem("ab"));
        assertNotNull(NameRules.problem(""));
        assertNull(NameRules.problem("*abc*"));
        assertFalse(NameRules.matches("*", "anyone"), "even if one were stored it never matches");
    }

    @Test
    void ruleLengthIsLimitedAndLineBreaksRefused() {
        assertNotNull(NameRules.problem("x".repeat(65)));
        assertNotNull(NameRules.problem("spam\nbot"));
    }

    @Test
    void cleaningTrimsLowercasesAndDropsBlanksAndRepeats() {
        assertEquals(List.of("spam*", "free*nitro"), NameRules.clean(List.of(" Spam* ", "", "FREE*nitro", "spam*")));
    }

    @Test
    void firstMatchLooksAtEveryNameOfThePerson() {
        assertEquals("*raid*", NameRules.firstMatch(List.of("*scam*", "*raid*"), java.util.Arrays.asList("Nice Guy", null, "RaidBot9")));
        assertNull(NameRules.firstMatch(List.of("*scam*"), List.of("Nice Guy", "Friendly")));
        assertNull(NameRules.firstMatch(List.of(), List.of("Anyone")));
    }
}
