package com.younglings.bot.alliance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AllianceRulesTest {
    @Test
    void anAllianceNeedsAPlainName() {
        assertNull(AllianceRules.nameProblem("Northern Clans"));
        assertNull(AllianceRules.nameProblem("Jon & Co."));
        assertNotNull(AllianceRules.nameProblem("ab"));
        assertNotNull(AllianceRules.nameProblem("  "));
        assertNotNull(AllianceRules.nameProblem("<@everyone>"));
        assertNotNull(AllianceRules.nameProblem("x".repeat(AllianceRules.MAX_NAME + 1)));
    }

    @Test
    void aServerCanOnlyBeInSoManyAlliances() {
        assertNull(AllianceRules.serverLimitProblem(AllianceRules.MAX_PER_SERVER - 1));
        assertNotNull(AllianceRules.serverLimitProblem(AllianceRules.MAX_PER_SERVER));
    }

    @Test
    void invitingChecksSelfDuplicatesAndRoom() {
        assertNull(AllianceRules.inviteProblem(false, false, 3));
        assertNotNull(AllianceRules.inviteProblem(true, false, 3));
        assertNotNull(AllianceRules.inviteProblem(false, true, 3));
        assertNotNull(AllianceRules.inviteProblem(false, false, AllianceRules.MAX_MEMBERS));
    }
}
