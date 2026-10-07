package com.younglings.bot.commands.combat;

import com.younglings.bot.combat.CombatAchievementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombatAchievementCommandTest {
    @Test
    void aTierNameInAnyCaseIsItsNumberAndAnythingElseIsNone() {
        assertEquals(1, CombatAchievementCommand.tierNumber("Easy"));
        assertEquals(4, CombatAchievementCommand.tierNumber("elite"));
        assertEquals(6, CombatAchievementCommand.tierNumber(" GRANDMASTER "));
        assertNull(CombatAchievementCommand.tierNumber(""));
        assertNull(CombatAchievementCommand.tierNumber(null));
        assertNull(CombatAchievementCommand.tierNumber("Legendary"));
        assertNull(CombatAchievementCommand.tierNumber("General"));
    }

    @Test
    void aBossIsMatchedIgnoringCaseAndComesBackAsTheCatalogueSpellsIt() {
        CombatAchievementRepository repository = mock(CombatAchievementRepository.class);
        when(repository.bosses()).thenReturn(List.of("Amascut", "Arch-Glacor", "Boss Dungeon: Sanctum of Rebirth"));
        CombatAchievementCommand command = new CombatAchievementCommand(repository);

        assertEquals("Arch-Glacor", command.canonicalBoss("arch-glacor"));
        assertEquals("Boss Dungeon: Sanctum of Rebirth", command.canonicalBoss("  boss dungeon: sanctum of rebirth "));
        assertNull(command.canonicalBoss("Astellarn"), "a boss inside a dungeon isn't a boss of its own");
        assertNull(command.canonicalBoss(""));
        assertNull(command.canonicalBoss(null));
    }
}
