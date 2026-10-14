package com.younglings.bot.events;

import com.younglings.bot.events.BingoBoard.Board;
import com.younglings.bot.events.BingoBoard.Tile;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bingo: a square board of tiles, each an objective, with bonuses for rows, columns, diagonals and a blackout. Settings: {@code board.size}
 * (3 to 7, 5 by default), {@code board.seed} (re-roll by changing it), {@code lineBonus} (50) and {@code blackoutBonus} (250). The board's
 * tile order is kept as {@code board.keys}; the tiles themselves are ordinary objectives, so everything the engine does (proof, teams, buy-ins,
 * prizes) works on them unchanged, and an organiser can edit any tile's title or points afterwards.
 */
@BService
public class BingoTemplate implements EventTemplate {
    public static final String KEY = "bingo";
    public static final int DEFAULT_SIZE = 5;
    public static final long DEFAULT_LINE_BONUS = 50;
    public static final long DEFAULT_BLACKOUT_BONUS = 250;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public java.util.Set<String> fixedSettings() {
        return java.util.Set.of("board", "bonuses", "lineBonus", "blackoutBonus");
    }

    @Override
    public String label() {
        return "Bingo";
    }

    @Override
    public String description() {
        return "A board of tiles. Complete tiles for points, and earn bonuses for rows, columns, diagonals and a full blackout. Pick the size, re-roll the board, lock the tiles you like.";
    }

    /** The pool a board is made from: the one named in the settings, drops by default. */
    static String poolOf(DataObject config) {
        try {
            DataObject board = config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty();
            String id = board.getString("pool", BingoBoard.POOL_DROPS);
            return BingoBoard.poolIds().contains(id) ? id : BingoBoard.POOL_DROPS;
        } catch (RuntimeException e) {
            return BingoBoard.POOL_DROPS;
        }
    }

    private static int size(DataObject config) {
        try {
            return config.hasKey("board") && !config.isNull("board") ? config.getObject("board").getInt("size", DEFAULT_SIZE) : DEFAULT_SIZE;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long number(DataObject config, String key, long fallback) {
        try {
            return config.hasKey(key) && !config.isNull(key) ? config.getLong(key) : fallback;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public List<String> configProblems(DataObject config) {
        List<String> problems = new ArrayList<>(BingoBoard.problems(size(config), BingoBoard.pool(poolOf(config)).size(), number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS)));
        try {
            if (config.hasKey("board") && !config.isNull("board") && config.getObject("board").hasKey("pool")
                    && !BingoBoard.poolIds().contains(config.getObject("board").getString("pool", ""))) problems.add("Choose a tile pool from the list.");
        } catch (RuntimeException e) {
            problems.add("The board settings couldn't be read.");
        }
        return problems;
    }

    /** A new bingo event gets a board straight away: random seed unless one is given. */
    @Override
    public DataObject prepare(DataObject config) {
        DataObject board = config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty();
        if (board.hasKey("keys") && !board.isNull("keys")) return config;
        long seed = board.getLong("seed", System.nanoTime() & 0xFFFFFFFFL);
        String pool = poolOf(config);
        Board generated = BingoBoard.generate(board.getInt("size", DEFAULT_SIZE), seed, BingoBoard.pool(pool), null, Set.of(),
                number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
        return withBoard(config, generated, pool);
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        if (!config.hasKey("board") || config.isNull("board")) return List.of();
        DataObject board = config.getObject("board");
        int size = board.getInt("size", DEFAULT_SIZE);
        // the same size and seed give the same tiles, so the objectives can be rebuilt from the settings alone
        Board rebuilt = BingoBoard.generate(size, board.getLong("seed", 0), BingoBoard.pool(poolOf(config)), null, Set.of(),
                number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
        return rebuilt.tiles().stream().map(BingoTemplate::objective).toList();
    }

    /** The new settings and objectives after re-rolling: locked tiles stay where they are, other objectives an organiser added are kept. */
    public record Rerolled(DataObject config, List<ObjectiveSpec> objectives) {}

    public Rerolled reroll(DataObject config, List<ObjectiveSpec> current, Integer newSize, Long newSeed, Set<String> lockedKeys) {
        DataObject board = config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty();
        int size = newSize != null ? newSize : board.getInt("size", DEFAULT_SIZE);
        long seed = newSeed != null ? newSeed : (System.nanoTime() & 0xFFFFFFFFL);
        Map<String, ObjectiveSpec> byKey = current.stream().collect(Collectors.toMap(ObjectiveSpec::key, Function.identity(), (a, b) -> a));
        List<Tile> previous = new ArrayList<>();
        List<String> oldKeys = new ArrayList<>();
        if (board.hasKey("keys") && !board.isNull("keys")) {
            DataArray keys = board.getArray("keys");
            for (int i = 0; i < keys.length(); i++) oldKeys.add(keys.getString(i));
        }
        for (String key : oldKeys) {
            ObjectiveSpec o = byKey.get(key);
            previous.add(o == null ? new Tile(key, key, 0, 1) : new Tile(o.key(), o.label(), o.points(), o.target()));
        }
        // a board of a different size can't keep tiles in place: only a same-size re-roll honours locks
        boolean sameSize = size == board.getInt("size", DEFAULT_SIZE) && previous.size() == size * size;
        String pool = poolOf(config);
        Board generated = BingoBoard.generate(size, seed, BingoBoard.pool(pool), sameSize ? previous : null, sameSize ? lockedKeys : Set.of(),
                number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
        Set<String> boardKeys = generated.tiles().stream().map(Tile::key).collect(Collectors.toSet());
        List<ObjectiveSpec> objectives = new ArrayList<>();
        for (Tile tile : generated.tiles()) {
            // a tile that stayed keeps whatever the organiser changed about it (evidence, description, prerequisites)
            ObjectiveSpec existing = byKey.get(tile.key());
            objectives.add(existing != null && lockedKeys.contains(tile.key()) ? existing : objective(tile));
        }
        for (ObjectiveSpec extra : current) if (!oldKeys.contains(extra.key()) && !boardKeys.contains(extra.key())) objectives.add(extra);
        return new Rerolled(withBoard(config, generated, pool), objectives);
    }

    private static ObjectiveSpec objective(Tile tile) {
        return new ObjectiveSpec(tile.key(), tile.label(), "", tile.points(), Evidence.EITHER, tile.target(), 1, List.of());
    }

    private static DataObject withBoard(DataObject config, Board board, String pool) {
        DataArray keys = DataArray.empty();
        board.tiles().forEach(t -> keys.add(t.key()));
        DataObject copy = DataObject.fromJson(config.toString());
        copy.put("board", DataObject.empty().put("size", board.size()).put("seed", board.seed()).put("pool", pool).put("keys", keys));
        copy.put("bonuses", EventBonuses.toJson(board.bonuses()));
        return copy;
    }
}
