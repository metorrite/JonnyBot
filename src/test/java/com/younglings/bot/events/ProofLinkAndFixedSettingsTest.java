package com.younglings.bot.events;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProofLinkAndFixedSettingsTest {
    @Test
    void onlyWebLinksAreAcceptedAsProof() {
        assertNull(EventRules.proofUrlProblem(null));
        assertNull(EventRules.proofUrlProblem("  "));
        assertNull(EventRules.proofUrlProblem("https://i.imgur.com/abc.png"));
        assertNull(EventRules.proofUrlProblem("HTTP://example.com/a?b=c"));
        assertNotNull(EventRules.proofUrlProblem("javascript:alert(1)"));
        assertNotNull(EventRules.proofUrlProblem("data:text/html,<script>1</script>"));
        assertNotNull(EventRules.proofUrlProblem("ftp://example.com/a"));
        assertNotNull(EventRules.proofUrlProblem("https://example.com/a b"));
        assertNotNull(EventRules.proofUrlProblem("https://example.com/<x>"));
        assertNotNull(EventRules.proofUrlProblem("https://example.com/" + "a".repeat(500)));
    }

    @Test
    void eachTemplateFixesTheSettingsItBuildsObjectivesFrom() {
        assertEquals(Set.of(), new CustomTemplate().fixedSettings());
        assertEquals(Set.of("board", "bonuses", "lineBonus", "blackoutBonus"), new BingoTemplate().fixedSettings());
        assertEquals(Set.of("skillId", "xpPerPoint", "xpSource", "confirmedLinksOnly", "wholeSpan"), new CompetitionTemplate().fixedSettings());
        assertEquals(Set.of("boss", "kills", "pointsPerKill"), new BossNightTemplate().fixedSettings());
        assertEquals(true, new ScavengerHuntTemplate().fixedSettings().contains("bonuses"));
    }
}
