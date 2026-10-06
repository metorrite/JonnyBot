package com.younglings.bot.internal;

import com.younglings.bot.internal.ActivityKinds.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityKindsTest {
    @Test
    void recognisesTheCommonLines() {
        assertEquals(Kind.LEVEL_UP, ActivityKinds.kindOf("Levelled up Cooking."));
        assertEquals(Kind.XP_MILESTONE, ActivityKinds.kindOf("200000000XP in Necromancy"));
        assertEquals(Kind.QUEST, ActivityKinds.kindOf("Quest complete: Heralds of Crimson"));
        assertEquals(Kind.CITADEL_CAP, ActivityKinds.kindOf("Capped at my Clan Citadel."));
        assertEquals(Kind.CITADEL_VISIT, ActivityKinds.kindOf("Visited my Clan Citadel."));
        assertEquals(Kind.BOSS, ActivityKinds.kindOf("I killed 12 Amascuts."));
        assertEquals(Kind.OTHER, ActivityKinds.kindOf("Maintained Clan Fealty 5"));
    }

    @Test
    void killLinesGiveTheBossAndCount() {
        assertEquals("Vorago", ActivityKinds.bossOf("I killed  Vorago.").orElseThrow());
        assertEquals(1, ActivityKinds.killCount("I killed  Vorago."));
        assertEquals(12, ActivityKinds.killCount("I killed 12 Amascuts."));
        assertEquals(1_200, ActivityKinds.killCount("I killed 1,200 Telos."));
        assertEquals("Telos", ActivityKinds.bossOf("I killed 21 Telos.").orElseThrow());
    }

    @Test
    void bossesOutsideTheCatalogueAreReadFromTheLine() {
        assertEquals("Amascut", ActivityKinds.bossOf("I killed 12 Amascuts.").orElseThrow());
        assertEquals("Nex", ActivityKinds.bossOf("I killed 7 Nexes.").orElseThrow());
        assertEquals("Gate of Elidinis", ActivityKinds.bossOf("I killed 18 Gate of Elidinis.").orElseThrow());
        assertEquals("Amascut", ActivityKinds.bossOf("I killed  Amascut, the Devourer.").orElseThrow());
        assertEquals("Arch-Glacor", ActivityKinds.bossOf("I killed  an Arch-Glacor.").orElseThrow());
        assertEquals("King Black Dragon", ActivityKinds.bossOf("I killed 15 King Black Dragons.").orElseThrow());
    }

    @Test
    void nonKillLinesHaveNoBoss() {
        assertTrue(ActivityKinds.bossOf("Levelled up Mining.").isEmpty());
        assertTrue(ActivityKinds.bossOf("I found a dragon helm").isEmpty());
    }
}
