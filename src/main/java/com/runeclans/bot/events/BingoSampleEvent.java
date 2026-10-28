package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardImage.TileView;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Entry;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * A made-up bingo with made-up players, for looking at how a board shape and look turn out without running a real event. It builds the board the
 * way a real event does (the template's own tiles, prerequisites and bonuses), then plays it: six players of different skill complete tiles in the
 * order the shape allows, so a tile is only ever done by someone who has done what it needs. The tiles are then drawn from the standings exactly as
 * the live board is. Pure and seeded, so the same seed gives the same event.
 */
public final class BingoSampleEvent {
    public static final List<String> PLAYERS = List.of("Metorrite", "Atrum Dies", "Hodant", "Xtusy", "S1GENERAL", "UntamedWrath");
    private static final double[] SKILL = {0.95, 0.82, 0.68, 0.5, 0.34, 0.18};

    /** The made-up event: its settings, objectives, and the tiles to draw. */
    public record Sample(DataObject config, List<ObjectiveSpec> objectives, List<TileView> tiles) {
        public int completed() {
            return (int) tiles.stream().filter(TileView::done).count();
        }
    }

    private BingoSampleEvent() {}

    /** @param iconOf the picture key for a tile's label, or null (as {@link BingoTiles#iconKeyFor}) */
    public static Sample build(BingoTemplate template, BingoLayout layout, Function<String, String> iconOf, long seed) {
        DataObject board = DataObject.empty().put("layout", layout.id()).put("pool", BingoBoard.POOL_DROPS).put("unlock", BingoTemplate.UNLOCK_LOCKED).put("seed", seed);
        return play(template, board, layout, iconOf, seed);
    }

    /** The same for a square grid of {@code size} by {@code size}; every tile is open from the start, as a grid's are. */
    public static Sample buildGrid(BingoTemplate template, int size, Function<String, String> iconOf, long seed) {
        DataObject board = DataObject.empty().put("size", size).put("pool", BingoBoard.POOL_DROPS).put("seed", seed);
        return play(template, board, null, iconOf, seed);
    }

    private static Sample play(BingoTemplate template, DataObject board, BingoLayout layout, Function<String, String> iconOf, long seed) {
        DataObject config = template.prepare(DataObject.empty().put("board", board));
        List<ObjectiveSpec> objectives = template.starterObjectives(config);
        List<String> keys = BingoBoardView.boardKeys(config);
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        objectives.forEach(o -> byKey.put(o.key(), o));

        // the tiles in the order the journey goes: by how far along the shape each is, then by number
        List<Integer> order = new ArrayList<>();
        for (int n = 1; n <= keys.size(); n++) order.add(n);
        order.sort(Comparator.comparingInt((Integer n) -> layout == null ? 0 : layout.depth(n)).thenComparingInt(n -> n));

        Random random = new Random(seed * 31 + 7);
        Instant start = Instant.parse("2026-10-16T18:00:00Z");
        List<Entry> log = new ArrayList<>();
        List<Set<String>> done = new ArrayList<>();
        for (int i = 0; i < PLAYERS.size(); i++) done.add(new HashSet<>());
        int step = 0;
        for (int n : order) {
            ObjectiveSpec objective = byKey.get(keys.get(n - 1));
            double hardness = layout == null ? 0.2 : 1.0 - 0.13 * layout.depth(n) / Math.max(1, maxDepth(layout)) * 4;
            for (int i = 0; i < PLAYERS.size(); i++) {
                step++;
                boolean open = done.get(i).containsAll(objective.prerequisites());
                if (open && random.nextDouble() < SKILL[i] * Math.max(0.15, hardness)) {
                    done.get(i).add(objective.key());
                    log.add(new Entry("u:" + i, objective.key(), objective.target(), start.plusSeconds(step * 240L)));
                }
            }
        }

        var standings = EventScoring.standings(objectives, log, EventBonuses.parse(config));
        Map<String, String> firstBy = new HashMap<>();
        BingoBoardView.firstCompleters(objectives, log).forEach((key, subject) -> firstBy.put(key, PLAYERS.get(Integer.parseInt(subject.substring(2)))));
        List<TileView> tiles = BingoBoardView.shared(keys, objectives, standings, PLAYERS.size(), iconOf, firstBy);
        return new Sample(config, objectives, tiles);
    }

    private static int maxDepth(BingoLayout layout) {
        int max = 0;
        for (int n = 1; n <= layout.tiles(); n++) max = Math.max(max, layout.depth(n));
        return max;
    }
}
