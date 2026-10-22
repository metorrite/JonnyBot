package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.plugin.PluginEventType;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginObjectiveMatcherTest {
    /** Reads "Boss: item" labels the way the real catalogue does, without needing it. */
    private static final PluginObjectiveMatcher.Labels LABELS = label -> {
        int colon = label.indexOf(": ");
        return colon > 0 ? new BingoTiles.Ref(label.substring(0, colon), label.substring(colon + 2).strip(), null) : new BingoTiles.Ref(null, label.strip(), null);
    };

    private static ObjectiveSpec tile(String key, String label, Evidence evidence) {
        return new ObjectiveSpec(key, label, "", 1, evidence, 1, 1, List.of());
    }

    private static DataObject json(String text) {
        return DataObject.fromJson(text);
    }

    @Test
    void aDropCountsTowardsTheBingoTileForThatItem() {
        var tiles = List.of(tile("t1", "Nex: Torva boots", Evidence.EITHER), tile("t2", "Araxxor: Noxious blade", Evidence.EITHER));

        var hits = PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.DROP, null, json("{\"itemName\":\"torva boots\"}"), LABELS);

        assertEquals(List.of(new PluginObjectiveMatcher.Hit("t1", 1)), hits);
    }

    @Test
    void aTileForAnotherBossesDropDoesNotCountWhenThePluginNamesTheMonster() {
        var tiles = List.of(tile("t1", "Nex: Torva boots", Evidence.AUTO));

        assertTrue(PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.DROP, "Araxxor", json("{\"itemName\":\"Torva boots\"}"), LABELS).isEmpty());
        assertEquals(1, PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.DROP, "Nex", json("{\"itemName\":\"Torva boots\"}"), LABELS).size());
        assertEquals(1, PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.DROP, null, json("{\"itemName\":\"Torva boots\"}"), LABELS).size(), "no monster named: the item is enough");
    }

    @Test
    void objectivesThatNeedProofOrAnOrganiserNeverTakeAutomaticProgress() {
        var tiles = List.of(tile("proof", "Nex: Torva boots", Evidence.PROOF), tile("org", "Torva boots", Evidence.ORGANISER));

        assertTrue(PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.DROP, null, json("{\"itemName\":\"Torva boots\"}"), LABELS).isEmpty());
    }

    @Test
    void aStackOfDropsCountsItsQuantity() {
        var tiles = List.of(new ObjectiveSpec("t1", "Shark", "", 1, Evidence.AUTO, 100, 1, List.of()));

        var hits = PluginObjectiveMatcher.match("custom", "{}", tiles, PluginEventType.DROP, null, json("{\"itemName\":\"Shark\",\"quantity\":12}"), LABELS);

        assertEquals(List.of(new PluginObjectiveMatcher.Hit("t1", 12)), hits);
    }

    @Test
    void aKillOfTheNightsBossIsOneKill() {
        var objectives = List.of(tile(BossNightTemplate.OBJECTIVE_KEY, "Kill Telos", Evidence.EITHER));
        String config = "{\"boss\":\"Telos\",\"kills\":10}";

        assertEquals(List.of(new PluginObjectiveMatcher.Hit(BossNightTemplate.OBJECTIVE_KEY, 1)),
                PluginObjectiveMatcher.match(BossNightTemplate.KEY, config, objectives, PluginEventType.KILL, null, json("{\"bossName\":\"telos\"}"), LABELS));
        assertTrue(PluginObjectiveMatcher.match(BossNightTemplate.KEY, config, objectives, PluginEventType.KILL, null, json("{\"bossName\":\"Nex\"}"), LABELS).isEmpty());
    }

    @Test
    void killsDropsAndOtherThingsDoNotCrossOverBetweenTemplates() {
        var objectives = List.of(tile(BossNightTemplate.OBJECTIVE_KEY, "Kill Telos", Evidence.EITHER));
        var tiles = List.of(tile("t1", "Telos", Evidence.AUTO));

        assertTrue(PluginObjectiveMatcher.match(BossNightTemplate.KEY, "{\"boss\":\"Telos\"}", objectives, PluginEventType.DROP, null, json("{\"itemName\":\"Telos\"}"), LABELS).isEmpty(), "a drop is not a boss night kill");
        assertTrue(PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.KILL, null, json("{\"bossName\":\"Telos\"}"), LABELS).isEmpty(), "a kill does not tick a bingo tile");
        assertTrue(PluginObjectiveMatcher.match(CompetitionTemplate.KEY, "{}", tiles, PluginEventType.DROP, null, json("{\"itemName\":\"Telos\"}"), LABELS).isEmpty(), "competitions are fed by tracked xp");
        assertTrue(PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.LEVEL_UP, null, json("{\"skill\":\"Telos\",\"level\":99}"), LABELS).isEmpty());
    }

    @Test
    void aMissingItemOrBrokenConfigMatchesNothingInsteadOfFailing() {
        var tiles = List.of(tile("t1", "Torva boots", Evidence.AUTO));

        assertTrue(PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.DROP, null, json("{}"), LABELS).isEmpty());
        assertTrue(PluginObjectiveMatcher.match(BossNightTemplate.KEY, "not json", List.of(tile(BossNightTemplate.OBJECTIVE_KEY, "Kill", Evidence.AUTO)), PluginEventType.KILL, null,
                json("{\"bossName\":\"Telos\"}"), LABELS).isEmpty());
        assertEquals("t1", tiles.get(0).key().toLowerCase(Locale.ROOT));
    }
    @Test
    void aClueCountsTowardsAClueTileOfItsTierOrAnyTier() {
        var tiles = List.of(tile("elite", "Elite clue", Evidence.AUTO), tile("hard", "Clue scroll (hard)", Evidence.AUTO), tile("any", "Any clue", Evidence.EITHER),
                tile("drop", "Elite clue: Spectral shield", Evidence.AUTO));

        var hits = PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.CLUE, null, json("{\"tier\":\"elite\",\"count\":12}"), LABELS);

        assertEquals(List.of(new PluginObjectiveMatcher.Hit("elite", 1), new PluginObjectiveMatcher.Hit("any", 1)), hits);
    }

    @Test
    void aLevelUpCountsWhenItReachesTheTilesLevel() {
        var tiles = List.of(tile("a", "99 Fletching", Evidence.AUTO), tile("b", "Level 90 Herblore", Evidence.AUTO), tile("c", "Fletching 100", Evidence.AUTO),
                tile("d", "Torva boots", Evidence.AUTO));

        var hits = PluginObjectiveMatcher.match("scavenger", "{}", tiles, PluginEventType.LEVEL_UP, null, json("{\"skill\":\"Fletching\",\"level\":99}"), LABELS);

        assertEquals(List.of(new PluginObjectiveMatcher.Hit("a", 1)), hits);
    }

    @Test
    void collectionLogQuestsAndAchievementsNeedTheirPrefixSoNothingCountsTwice() {
        var tiles = List.of(tile("item", "Torva boots", Evidence.AUTO), tile("log", "Collection log: Torva boots", Evidence.AUTO), tile("quest", "Quest: Desperate Times", Evidence.AUTO),
                tile("ach", "Achievement: Master Quester", Evidence.AUTO));

        assertEquals(List.of(new PluginObjectiveMatcher.Hit("log", 1)),
                PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.COLLECTION_LOG, null, json("{\"itemName\":\"Torva boots\"}"), LABELS));
        assertEquals(List.of(new PluginObjectiveMatcher.Hit("quest", 1)),
                PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.QUEST_COMPLETE, null, json("{\"quest\":\"desperate times\"}"), LABELS));
        assertEquals(List.of(new PluginObjectiveMatcher.Hit("ach", 1)),
                PluginObjectiveMatcher.match("bingo", "{}", tiles, PluginEventType.ACHIEVEMENT, null, json("{\"name\":\"Master Quester\"}"), LABELS));
    }

    @Test
    void theNewTypesDontCountInCompetitionsOrBossNights() {
        var tiles = List.of(tile("elite", "Elite clue", Evidence.AUTO));
        assertTrue(PluginObjectiveMatcher.match(CompetitionTemplate.KEY, "{}", tiles, PluginEventType.CLUE, null, json("{\"tier\":\"elite\",\"count\":1}"), LABELS).isEmpty());
        assertTrue(PluginObjectiveMatcher.match(BossNightTemplate.KEY, "{}", tiles, PluginEventType.CLUE, null, json("{\"tier\":\"elite\",\"count\":1}"), LABELS).isEmpty());
    }

    // ---------- the editor's hints (T-141): they must agree with match() ----------

    private static final java.util.function.Function<String, Boolean> KNOWN = item -> item.equalsIgnoreCase("Torva boots");

    private static PluginObjectiveMatcher.Hint hint(String label) {
        return PluginObjectiveMatcher.explain("bingo", label, LABELS, KNOWN);
    }

    @Test
    void aHintSaysAutoExactlyWhenARuneSyncReportWouldTickTheTile() {
        // a known drop: auto, and a drop report of it ticks the tile
        assertTrue(hint("Nex: Torva boots").auto());
        assertTrue(hint("Nex: Torva boots").text().contains("Torva boots from Nex"));
        assertEquals(1, PluginObjectiveMatcher.match("bingo", "{}", List.of(tile("t", "Nex: Torva boots", Evidence.EITHER)), PluginEventType.DROP, "Nex",
                json("{\"itemName\":\"Torva boots\"}"), LABELS).size());
        // clue tiles: auto, and a clue report ticks them
        assertTrue(hint("Elite clue").auto());
        assertTrue(hint("Any clue scroll").auto());
        assertEquals(1, PluginObjectiveMatcher.match("bingo", "{}", List.of(tile("t", "Elite clue", Evidence.EITHER)), PluginEventType.CLUE, null,
                json("{\"tier\":\"elite\",\"count\":1}"), LABELS).size());
        // a clue label with two tiers can never match, and says so
        assertFalse(hint("Hard or elite clue").auto());
    }

    @Test
    void typesRuneSyncDoesNotReportYetAreNotPromised() {
        assertFalse(hint("99 Fletching").auto());
        assertTrue(hint("99 Fletching").text().contains("level-ups"));
        assertFalse(hint("Collection log: Abyssal whip").auto());
        assertFalse(hint("Quest: Dragon Slayer").auto());
        assertFalse(hint("Achievement: Ace of Spades").auto());
    }

    @Test
    void anUnknownItemIsFlaggedAndOtherEventTypesGetNoHints() {
        assertFalse(hint("Shiny rock").auto());
        assertTrue(hint("Shiny rock").text().contains("Not an item JonnyBot knows"));
        assertEquals(null, PluginObjectiveMatcher.explain(CompetitionTemplate.KEY, "Nex: Torva boots", LABELS, KNOWN));
        assertEquals(null, PluginObjectiveMatcher.explain(BossNightTemplate.KEY, "Nex: Torva boots", LABELS, KNOWN));
        assertEquals(null, hint("   "));
    }
}
