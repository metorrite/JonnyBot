package com.younglings.bot.warning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WarnPolicyTest {
    @Test
    void anOrdinaryWarningIsAllowed() {
        assertNull(WarnPolicy.refusal(false, false, true, "Spamming the channel"));
    }

    @Test
    void youCannotWarnYourselfOrABot() {
        assertEquals("You can't warn yourself.", WarnPolicy.refusal(true, false, true, "x"));
        assertEquals("Bots can't be warned.", WarnPolicy.refusal(false, true, true, "x"));
    }

    @Test
    void roleHierarchyIsRespected() {
        assertNotNull(WarnPolicy.refusal(false, false, false, "x"));
    }

    @Test
    void aReasonIsRequiredAndBounded() {
        assertNotNull(WarnPolicy.refusal(false, false, true, "   "));
        assertNotNull(WarnPolicy.refusal(false, false, true, null));
        assertNull(WarnPolicy.refusal(false, false, true, "a".repeat(WarnPolicy.MAX_REASON)));
        assertNotNull(WarnPolicy.refusal(false, false, true, "a".repeat(WarnPolicy.MAX_REASON + 1)));
    }

    @Test
    void countsReadNaturally() {
        assertEquals("1 warning", WarnPolicy.count(1));
        assertEquals("0 warnings", WarnPolicy.count(0));
        assertEquals("4 warnings", WarnPolicy.count(4));
    }
}
