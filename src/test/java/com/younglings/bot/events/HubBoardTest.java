package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Progress;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Source;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bingo board RuneClans shows (T-336): tiles, who did what, what the plugin ticked, and never a proof picture or a Discord id. */
class HubBoardTest {
    private static final Instant AT = Instant.parse("2026-10-20T12:00:00Z");

    private static ObjectiveSpec tile(String key, String label, long points, List<String> needs) {
        return new ObjectiveSpec(key, label, "", points, Evidence.EITHER, 1, 1, needs);
    }

    private static Progress progress(String key, long user, Long team, Source source) {
        return new Progress(1, 7, key, user, team, 1, source, "k", null, "", AT);
    }

    private static DataObject config(String... keys) {
        DataArray array = DataArray.empty();
        for (String k : keys) array.add(k);
        return DataObject.empty().put("board", DataObject.empty().put("size", 2).put("keys", array));
    }

    private static List<Integer> ints(DataArray array) {
        return java.util.stream.IntStream.range(0, array.length()).mapToObj(array::getInt).toList();
    }

    @Test
    void anEventWithoutABoardHasNone() {
        assertNull(HubBoard.of(DataObject.empty(), List.of(), List.of(), null, List.of(), false));
    }

    @Test
    void tilesCarryTheirLabelPointsAndWhatMustBeDoneFirst() {
        List<ObjectiveSpec> objectives = List.of(tile("a", "Vorago: Seismic wand", 10, List.of()), tile("b", "Telos kill", 20, List.of("a")),
                tile("c", "Any pet", 50, List.of("a", "b")));
        DataObject board = HubBoard.of(config("a", "b", BingoBoardOptions.FREE_KEY, "c"), objectives, List.of(), null, List.of(), false);

        assertEquals(2, board.getInt("columns"));
        DataArray tiles = board.getArray("tiles");
        assertEquals(4, tiles.length());
        assertEquals("Vorago: Seismic wand", tiles.getObject(0).getString("label"));
        assertEquals(List.of(1), ints(tiles.getObject(1).getArray("needs")));
        assertEquals(List.of(1, 2), ints(tiles.getObject(3).getArray("needs")));
        assertTrue(tiles.getObject(2).getBoolean("free"));
        assertEquals(0, board.getArray("rows").length(), "no rows unless the page may show them");
        assertTrue(board.isNull("mine"));
    }

    @Test
    void eachShownTeamListsItsDoneTilesAndThePluginTicksAmongThem() {
        List<ObjectiveSpec> objectives = List.of(tile("a", "A", 10, List.of()), tile("b", "B", 10, List.of()), tile("c", "C", 10, List.of()));
        var red = new HubBoard.Subject("t:1", "Red", 20, Map.of("a", 1, "b", 1));
        var blue = new HubBoard.Subject("t:2", "Blue", 10, Map.of("c", 1));
        List<Progress> log = List.of(progress("a", 5, 1L, Source.AUTO), progress("b", 6, 1L, Source.CLAIM), progress("c", 7, 2L, Source.MANUAL),
                progress("c", 8, null, Source.AUTO));

        DataObject board = HubBoard.of(config("a", "b", "c"), objectives, List.of(red, blue), blue, log, true);

        DataObject first = board.getArray("rows").getObject(0);
        assertEquals("Red", first.getString("name"));
        assertEquals(List.of(1, 2), ints(first.getArray("done")));
        assertEquals(List.of(1), ints(first.getArray("auto")), "only the tile the plugin ticked");
        DataObject mine = board.getObject("mine");
        assertEquals(List.of(3), ints(mine.getArray("done")));
        assertEquals(List.of(), ints(mine.getArray("auto")), "a plugin tick by someone on no team counts for no team");
        assertFalse(board.toString().contains("proof"), "never a proof picture");
    }

    @Test
    void soloBoardsCountThePlayersOwnPluginTicks() {
        List<ObjectiveSpec> objectives = List.of(tile("a", "A", 10, List.of()));
        var me = new HubBoard.Subject("u:5", "", 10, Map.of("a", 1));
        DataObject board = HubBoard.of(config("a"), objectives, List.of(), me, List.of(progress("a", 5, null, Source.AUTO)), false);
        assertEquals(List.of(1), ints(board.getObject("mine").getArray("auto")));
    }
}
