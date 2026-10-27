package com.younglings.bot.internal;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-586: the "In game now" widget's rules, without a database. */
class InGameNowRulesTest {
    private static DataObject layout(String widgets) {
        return DataObject.fromJson("""
                {"v":2,"pages":[{"id":"home","sections":[{"id":"s1","rows":[{"id":"r1","cells":[{"id":"c1","widgets":[%s]}]}]}]}]}
                """.formatted(widgets));
    }

    @Test
    void namesNobodyUntilTheWidgetIsPlaced() {
        assertNull(InGameNowRules.visibility(null));
        assertNull(InGameNowRules.visibility(layout("{\"id\":\"w1\",\"type\":\"activity-feed\",\"settings\":{}}")));
        DataObject none = InGameNowRules.notPlaced();
        assertFalse(none.getBoolean("placed"));
        assertEquals(0, none.getArray("members").length());
    }

    @Test
    void membersOnlyUnlessASiteEditorMadeItPublic() {
        assertEquals("members", InGameNowRules.visibility(layout("{\"id\":\"w1\",\"type\":\"in-game-now\",\"settings\":{}}")));
        assertEquals("members", InGameNowRules.visibility(layout("{\"id\":\"w1\",\"type\":\"in-game-now\",\"settings\":{\"who\":\"members\"}}")));
        assertEquals("members", InGameNowRules.visibility(layout("{\"id\":\"w1\",\"type\":\"in-game-now\",\"settings\":{\"who\":\"anything else\"}}")));
        assertEquals("public", InGameNowRules.visibility(layout("{\"id\":\"w1\",\"type\":\"in-game-now\",\"settings\":{\"who\":\"public\"}}")));
        // a text that merely mentions the type is not a widget
        assertNull(InGameNowRules.visibility(layout("{\"id\":\"w1\",\"type\":\"text\",\"settings\":{\"body\":\"in-game-now\"}}")));
    }

    @Test
    void theClanIsTheServerOrTheRoster() {
        Set<String> roster = InGameNowRules.lower(List.of("Skill Queen", "Iron_Bob"));
        assertTrue(InGameNowRules.inClan(true, List.of(), roster), "in the bot's server");
        assertTrue(InGameNowRules.inClan(false, List.of("skill_queen"), roster), "a RuneLink name on the roster, spelled another way");
        assertTrue(InGameNowRules.inClan(false, List.of("iron bob"), roster));
        assertFalse(InGameNowRules.inClan(false, List.of("Someone Else"), roster), "not in the clan");
        assertFalse(InGameNowRules.inClan(false, List.of(), roster));
    }

    @Test
    void showsTheRosterSpellingFirst() {
        List<String> roster = List.of("Skill Queen");
        assertEquals("Skill Queen", InGameNowRules.displayName(List.of("Alt Account", "skill_queen"), roster), "the name on the roster wins");
        assertEquals("Playing Now", InGameNowRules.displayName(List.of("Playing Now", "Elsewhere"), roster), "else the character playing");
        assertNull(InGameNowRules.displayName(List.of(), roster), "no RuneScape name: never a Discord name");
    }

    @Test
    void sortsEachNameOnceAndCaps() {
        assertEquals(List.of("alpha", "Bravo", "charlie"), InGameNowRules.sorted(List.of("charlie", "Bravo", "alpha", "bravo")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < InGameNowRules.MAX_NAMES + 20; i++) many.add("Player " + i);
        assertEquals(InGameNowRules.MAX_NAMES, InGameNowRules.sorted(many).size());
    }

    @Test
    void theAnswerHoldsNamesOnly() {
        DataObject a = InGameNowRules.answer("public", List.of("Skill Queen"), 2);
        assertEquals("public", a.getString("visibility"));
        assertEquals(3, a.getInt("count"));
        assertEquals(2, a.getInt("unnamed"));
        assertEquals(11, a.getInt("windowMinutes"));
        DataObject m = a.getArray("members").getObject(0);
        assertEquals(Set.of("name"), m.keys(), "no world, no times, no ids");
    }
}
