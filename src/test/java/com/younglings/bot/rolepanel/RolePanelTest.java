package com.younglings.bot.rolepanel;

import com.younglings.bot.rolepanel.RolePanel.Mode;
import com.younglings.bot.rolepanel.RolePanel.RoleButton;
import com.younglings.bot.rolepanel.RolePanelListener.Change;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePanelTest {
    private static RolePanel panel(Mode mode, long... roles) {
        List<RoleButton> buttons = java.util.Arrays.stream(roles).mapToObj(r -> new RoleButton(r, "", null, "primary")).toList();
        return new RolePanel(1L, 5L, "Teams", "", "", null, mode, null, null, buttons);
    }

    @Test
    void buttonsAreSplitIntoRowsOfFive() {
        List<List<Integer>> rows = RolePanelMessage.rows(IntStream.rangeClosed(1, 12).boxed().toList());

        assertEquals(List.of(5, 5, 2), rows.stream().map(List::size).toList());
    }

    @Test
    void noButtonsMeansNoRows() {
        assertTrue(RolePanelMessage.rows(List.of()).isEmpty());
    }

    @Test
    void theButtonIdCarriesThePanelAndTheRole() {
        assertEquals("rr:7:42", RolePanelMessage.buttonId(7, 42));
    }

    @Test
    void anEmojiThatIsNotOneIsIgnoredAndARealOneIsKept() {
        assertNull(RolePanelMessage.emoji("   "));
        assertNotNull(RolePanelMessage.emoji("👍"));
    }

    @Test
    void pressingARoleTheMemberLacksGivesIt() {
        Change change = RolePanelListener.decide(panel(Mode.TOGGLE, 1, 2), new RoleButton(1, "", null, "primary"), Set.of(), "Red");

        assertEquals(List.of(1L), change.add());
        assertTrue(change.remove().isEmpty());
    }

    @Test
    void pressingARoleTheMemberHasTakesItAway() {
        Change change = RolePanelListener.decide(panel(Mode.TOGGLE, 1, 2), new RoleButton(1, "", null, "primary"), Set.of(1L), "Red");

        assertTrue(change.add().isEmpty());
        assertEquals(List.of(1L), change.remove());
    }

    @Test
    void inUniqueModeChoosingARoleSwapsOutTheOthersFromThePanelOnly() {
        // the member holds role 1 (from this panel) and role 99 (nothing to do with it)
        Change change = RolePanelListener.decide(panel(Mode.UNIQUE, 1, 2, 3), new RoleButton(2, "", null, "primary"), Set.of(1L, 99L), "Blue");

        assertEquals(List.of(2L), change.add());
        assertEquals(List.of(1L), change.remove());
        assertTrue(change.message().contains("instead"));
    }

    @Test
    void inToggleModeChoosingARoleLeavesTheOthersAlone() {
        Change change = RolePanelListener.decide(panel(Mode.TOGGLE, 1, 2), new RoleButton(2, "", null, "primary"), Set.of(1L), "Blue");

        assertEquals(List.of(2L), change.add());
        assertTrue(change.remove().isEmpty());
    }
}
