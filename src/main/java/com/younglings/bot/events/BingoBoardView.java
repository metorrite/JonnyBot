package com.younglings.bot.events;

import com.younglings.bot.events.BingoBoardImage.TileView;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns an event's board, objectives and standings into the tiles to draw. Pure. */
public final class BingoBoardView {
    private BingoBoardView() {}

    /** The board's objective keys in reading order, or an empty list when the event has no board. */
    public static List<String> boardKeys(DataObject config) {
        List<String> keys = new ArrayList<>();
        try {
            if (config.hasKey("board") && !config.isNull("board") && config.getObject("board").hasKey("keys")) {
                DataArray array = config.getObject("board").getArray("keys");
                for (int i = 0; i < array.length(); i++) keys.add(array.getString(i));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return keys;
    }

    public static int boardSize(DataObject config) {
        try {
            return config.getObject("board").getInt("size", 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * What a tile number means: the nth tile of the board when the event has one, otherwise the nth objective. Null when there is no such tile.
     * Numbers start at 1, as drawn on the board.
     */
    public static String keyForNumber(DataObject config, List<ObjectiveSpec> objectives, int number) {
        List<String> board = boardKeys(config);
        List<String> keys = board.isEmpty() ? objectives.stream().map(ObjectiveSpec::key).toList() : board;
        return number >= 1 && number <= keys.size() ? keys.get(number - 1) : null;
    }

    /** The shared board: each tile counts how many of the event's {@code subjects} have completed it. */
    public static List<TileView> shared(List<String> keys, List<ObjectiveSpec> objectives, List<Standing> standings, int subjects) {
        Map<String, ObjectiveSpec> byKey = byKey(objectives);
        List<TileView> tiles = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            ObjectiveSpec o = byKey.get(key);
            int count = (int) standings.stream().filter(s -> s.completions().getOrDefault(key, 0) > 0).count();
            tiles.add(new TileView(i + 1, o == null ? key : o.label(), o == null ? 0 : o.points(), count > 0, count, Math.max(subjects, count)));
        }
        return tiles;
    }

    /** One player's or team's board: a tile is done when they have completed it. {@code mine} may be null for someone with no progress. */
    public static List<TileView> personal(List<String> keys, List<ObjectiveSpec> objectives, Standing mine) {
        Map<String, ObjectiveSpec> byKey = byKey(objectives);
        Map<String, Integer> done = mine == null ? Map.of() : mine.completions();
        List<TileView> tiles = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            ObjectiveSpec o = byKey.get(keys.get(i));
            tiles.add(new TileView(i + 1, o == null ? keys.get(i) : o.label(), o == null ? 0 : o.points(), done.getOrDefault(keys.get(i), 0) > 0, 0, 0));
        }
        return tiles;
    }

    private static Map<String, ObjectiveSpec> byKey(List<ObjectiveSpec> objectives) {
        Map<String, ObjectiveSpec> map = new HashMap<>();
        for (ObjectiveSpec o : objectives) map.put(o.key(), o);
        return map;
    }
}
