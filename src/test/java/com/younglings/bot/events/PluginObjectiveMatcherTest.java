package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.plugin.PluginEventType;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
