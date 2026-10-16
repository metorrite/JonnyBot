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
 * Bingo: a board of tiles, each an objective, with bonuses for rows, columns, diagonals and a blackout. Settings: {@code board.size}
 * (3 to 7, 5 by default), {@code board.seed} (re-roll by changing it), {@code lineBonus} (50) and {@code blackoutBonus} (250). The board's
 * tile order is kept as {@code board.keys}; the tiles themselves are ordinary objectives, so everything the engine does (proof, teams, buy-ins,
 * prizes) works on them unchanged, and an organiser can edit any tile's title or points afterwards.
 * <p>
 * A board can instead have one of the picture shapes of {@link BingoLayout} ({@code board.layout}: a path, a pyramid, a convergence, a world tree).
 * Then the number of tiles is the shape's, {@code lineBonus} is the bonus for finishing a tier, row or ring, and {@code board.unlock} says whether
 * the shape's rules about which tiles open first are enforced ({@code "locked"}, as prerequisites on the tiles) or only the bonuses apply
 * ({@code "free"}). Locked suits tasks players can plan; for rare drops that can't be banked, free is the fairer choice, so it is the default
 * unless the tiles come from the classic pool.
 * <p>
 * {@code board.look} picks what the board is drawn on ({@link BingoLooks}); it never changes the rules, so it can be changed at any time before the event is over.
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

    /** The picture shape the board has, if it has one. */
    public static java.util.Optional<BingoLayout> layoutOf(DataObject config) {
        try {
            return config.hasKey("board") && !config.isNull("board") ? BingoLayout.find(config.getObject("board").getString("layout", null)) : java.util.Optional.empty();
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** Whether a shaped board's rules about which tiles open first are enforced. Unless the settings say, they are for the classic pool and not for drops. */
    public static boolean unlockedInOrder(DataObject config) {
        try {
            String unlock = config.hasKey("board") && !config.isNull("board") ? config.getObject("board").getString("unlock", "") : "";
            if (unlock.equals(UNLOCK_LOCKED)) return true;
            if (unlock.equals(UNLOCK_FREE)) return false;
        } catch (RuntimeException e) {
            return false;
        }
        return BingoBoard.POOL_CLASSIC.equals(poolOf(config));
    }

    public static final String UNLOCK_LOCKED = "locked";
    public static final String UNLOCK_FREE = "free";

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
        var layout = layoutOf(config);
        long lineBonus = number(config, "lineBonus", DEFAULT_LINE_BONUS);
        long blackoutBonus = number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS);
        List<String> problems = new ArrayList<>(layout.isPresent()
                ? BingoBoard.layoutProblems(layout.get(), BingoBoard.pool(poolOf(config)).size(), lineBonus, blackoutBonus)
                : BingoBoard.problems(size(config), BingoBoard.pool(poolOf(config)).size(), lineBonus, blackoutBonus));
        try {
            if (config.hasKey("board") && !config.isNull("board")) {
                DataObject board = config.getObject("board");
                if (board.hasKey("layout") && !board.isNull("layout") && layout.isEmpty()) problems.add("Choose a board shape from the list.");
                if (board.hasKey("unlock") && !board.isNull("unlock") && !List.of(UNLOCK_LOCKED, UNLOCK_FREE).contains(board.getString("unlock", ""))) {
                    problems.add("Tiles either unlock in order or in any order.");
                }
                if (board.hasKey("look") && !board.isNull("look") && !BingoLooks.valid(board.getString("look", ""))) {
                    problems.add("Choose a board look from the list.");
                }
            }
        } catch (RuntimeException e) {
            problems.add("The board settings couldn't be read.");
        }
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
        var layout = layoutOf(config);
        Board generated = layout.isPresent()
                ? BingoBoard.generateLayout(layout.get(), seed, BingoBoard.pool(pool), null, Set.of(), number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS))
                : BingoBoard.generate(board.getInt("size", DEFAULT_SIZE), seed, BingoBoard.pool(pool), null, Set.of(),
                        number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
        return withBoard(config, generated, pool, unlockedInOrder(config));
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        if (!config.hasKey("board") || config.isNull("board")) return List.of();
        DataObject board = config.getObject("board");
        var layout = layoutOf(config);
        // the same size (or shape) and seed give the same tiles, so the objectives can be rebuilt from the settings alone
        if (layout.isPresent()) {
            Board rebuilt = BingoBoard.generateLayout(layout.get(), board.getLong("seed", 0), BingoBoard.pool(poolOf(config)), null, Set.of(),
                    number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
            return layoutObjectives(layout.get(), rebuilt.tiles(), unlockedInOrder(config), null);
        }
        int size = board.getInt("size", DEFAULT_SIZE);
        Board rebuilt = BingoBoard.generate(size, board.getLong("seed", 0), BingoBoard.pool(poolOf(config)), null, Set.of(),
                number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
        return rebuilt.tiles().stream().map(BingoTemplate::objective).toList();
    }

    /** The new settings and objectives after re-rolling: locked tiles stay where they are, other objectives an organiser added are kept. */
    public record Rerolled(DataObject config, List<ObjectiveSpec> objectives) {}

    public Rerolled reroll(DataObject config, List<ObjectiveSpec> current, Integer newSize, Long newSeed, Set<String> lockedKeys) {
        return reroll(config, current, newSize, newSeed, lockedKeys, null);
    }

    /**
     * As above, and {@code newShape} may switch the board to another picture shape ({@link BingoLayout#ids}) or back to a square grid
     * ({@code "grid"}). Changing shape keeps no locked tiles: the places are different.
     */
    public Rerolled reroll(DataObject config, List<ObjectiveSpec> current, Integer newSize, Long newSeed, Set<String> lockedKeys, String newShape) {
        DataObject board = config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty();
        var oldLayout = layoutOf(config);
        if (newShape != null && !newShape.equals("grid") && BingoLayout.find(newShape).isEmpty()) throw new IllegalArgumentException("Choose a board shape from the list.");
        var layout = newShape == null ? oldLayout : newShape.equals("grid") ? java.util.Optional.<BingoLayout>empty() : BingoLayout.find(newShape);
        boolean shapeChanged = layout.map(BingoLayout::id).orElse("grid").compareTo(oldLayout.map(BingoLayout::id).orElse("grid")) != 0;
        if (shapeChanged) {
            // the old board's tiles go with the old shape; only objectives an organiser added beyond the board are kept
            List<String> oldBoardKeys = BingoBoardView.boardKeys(config);
            current = current.stream().filter(o -> !oldBoardKeys.contains(o.key())).toList();
            DataObject changed = DataObject.fromJson(config.toString());
            DataObject changedBoard = DataObject.fromJson(board.toString());
            if (layout.isPresent()) changedBoard.put("layout", layout.get().id());
            else changedBoard.remove("layout");
            changedBoard.remove("keys");
            changed.put("board", changedBoard);
            config = changed;
            board = changedBoard;
            lockedKeys = Set.of();
        }
        if (layout.isPresent()) return rerollShaped(config, current, newSeed, lockedKeys, layout.get(), shapeChanged);
        // a shaped board has no square size of its own, so coming back to a grid without one starts from the usual size
        int size = newSize != null ? newSize : shapeChanged ? DEFAULT_SIZE : board.getInt("size", DEFAULT_SIZE);
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
        boolean sameSize = !shapeChanged && size == board.getInt("size", DEFAULT_SIZE) && previous.size() == size * size;
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
        return new Rerolled(withBoard(config, generated, pool, false), objectives);
    }

    private Rerolled rerollShaped(DataObject config, List<ObjectiveSpec> current, Long newSeed, Set<String> lockedKeys, BingoLayout layout, boolean shapeChanged) {
        DataObject board = config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty();
        long seed = newSeed != null ? newSeed : (System.nanoTime() & 0xFFFFFFFFL);
        Map<String, ObjectiveSpec> byKey = current.stream().collect(Collectors.toMap(ObjectiveSpec::key, Function.identity(), (a, b) -> a));
        List<String> oldKeys = new ArrayList<>(BingoBoardView.boardKeys(config));
        List<Tile> previous = new ArrayList<>();
        for (String key : oldKeys) {
            ObjectiveSpec o = byKey.get(key);
            previous.add(o == null ? new Tile(key, key, 0, 1) : new Tile(o.key(), o.label(), o.points(), o.target()));
        }
        boolean keepLocks = !shapeChanged && previous.size() == layout.tiles();
        String pool = poolOf(config);
        Board generated = BingoBoard.generateLayout(layout, seed, BingoBoard.pool(pool), keepLocks ? previous : null, keepLocks ? lockedKeys : Set.of(),
                number(config, "lineBonus", DEFAULT_LINE_BONUS), number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS));
        boolean inOrder = unlockedInOrder(config);
        Map<String, ObjectiveSpec> kept = new java.util.HashMap<>();
        for (Tile tile : generated.tiles()) {
            ObjectiveSpec existing = byKey.get(tile.key());
            if (existing != null && keepLocks && lockedKeys.contains(tile.key())) kept.put(tile.key(), existing);
        }
        List<ObjectiveSpec> objectives = new ArrayList<>(layoutObjectives(layout, generated.tiles(), inOrder, kept));
        Set<String> boardKeys = generated.tiles().stream().map(Tile::key).collect(Collectors.toSet());
        for (ObjectiveSpec extra : current) if (!oldKeys.contains(extra.key()) && !boardKeys.contains(extra.key())) objectives.add(extra);
        return new Rerolled(withBoard(config, generated, pool, inOrder), objectives);
    }

    /**
     * The objectives of a shaped board, one per place in the picture's numbering. When the tiles open in order each needs the tiles the shape says (so a
     * tile can't be completed before them); otherwise nothing is required and the shape only earns its bonuses. A tile in {@code kept} keeps
     * everything an organiser changed about it (its evidence, description, target) except its prerequisites, which always follow the shape.
     */
    private static List<ObjectiveSpec> layoutObjectives(BingoLayout layout, List<Tile> tiles, boolean inOrder, Map<String, ObjectiveSpec> kept) {
        List<ObjectiveSpec> objectives = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            List<String> needs = inOrder ? layout.requires().getOrDefault(i + 1, List.of()).stream().map(n -> tiles.get(n - 1).key()).toList() : List.of();
            ObjectiveSpec existing = kept == null ? null : kept.get(tile.key());
            objectives.add(existing == null
                    ? new ObjectiveSpec(tile.key(), tile.label(), "", tile.points(), Evidence.EITHER, tile.target(), 1, needs)
                    : new ObjectiveSpec(existing.key(), existing.label(), existing.description(), existing.points(), existing.evidence(), existing.target(), existing.repeatLimit(), needs));
        }
        return objectives;
    }

    /**
     * Swaps one tile for another of the organiser's choosing: the tile at {@code number} (1 is the top left, reading across) gets a new title and
     * points and keeps its place. Its key follows the new title, the board's line and blackout bonuses are rebuilt to match, and the tile keeps
     * its evidence mode, target and description. Fails when the title is already a tile on this board.
     */
    public Rerolled replaceTile(DataObject config, List<ObjectiveSpec> current, int number, String label, long points) {
        DataObject board = config.hasKey("board") && !config.isNull("board") ? config.getObject("board") : DataObject.empty();
        List<String> keys = new ArrayList<>(BingoBoardView.boardKeys(config));
        if (keys.isEmpty()) throw new IllegalArgumentException("This event doesn't have a board yet.");
        if (number < 1 || number > keys.size()) throw new IllegalArgumentException("There's no tile " + number + ". The board has " + keys.size() + ".");
        String title = label == null ? "" : label.strip();
        String newKey = EventRules.keyFrom(title);
        if (title.isEmpty() || newKey.isEmpty()) throw new IllegalArgumentException("Give the tile a title.");
        String oldKey = keys.get(number - 1);
        if (!newKey.equals(oldKey) && current.stream().anyMatch(o -> o.key().equals(newKey))) {
            throw new IllegalArgumentException("There's already a tile or objective called \"" + title + "\". Pick a different title.");
        }
        Map<String, ObjectiveSpec> byKey = current.stream().collect(Collectors.toMap(ObjectiveSpec::key, Function.identity(), (a, b) -> a));
        ObjectiveSpec old = byKey.get(oldKey);
        List<ObjectiveSpec> objectives = new ArrayList<>();
        for (ObjectiveSpec o : current) {
            if (o.key().equals(oldKey)) {
                objectives.add(new ObjectiveSpec(newKey, title, o.description(), points, o.evidence(), o.target(), o.repeatLimit(), o.prerequisites()));
            } else {
                objectives.add(new ObjectiveSpec(o.key(), o.label(), o.description(), o.points(), o.evidence(), o.target(), o.repeatLimit(),
                        o.prerequisites().stream().map(p -> p.equals(oldKey) ? newKey : p).toList()));
            }
        }
        if (old == null) objectives.add(new ObjectiveSpec(newKey, title, "", points, Evidence.EITHER, 1, 1, List.of()));
        keys.set(number - 1, newKey);
        Map<String, ObjectiveSpec> after = objectives.stream().collect(Collectors.toMap(ObjectiveSpec::key, Function.identity(), (a, b) -> a));
        List<Tile> tiles = new ArrayList<>();
        for (String key : keys) {
            ObjectiveSpec o = after.get(key);
            tiles.add(new Tile(key, o == null ? key : o.label(), o == null ? 0 : o.points(), o == null ? 1 : o.target()));
        }
        var layout = layoutOf(config);
        int size = board.getInt("size", DEFAULT_SIZE);
        long lineBonus = number(config, "lineBonus", DEFAULT_LINE_BONUS);
        long blackoutBonus = number(config, "blackoutBonus", DEFAULT_BLACKOUT_BONUS);
        Board rebuilt = layout.isPresent()
                ? new Board(0, board.getLong("seed", 0), tiles, BingoBoard.layoutBonuses(layout.get(), tiles, lineBonus, blackoutBonus), layout.get().id())
                : new Board(size, board.getLong("seed", 0), tiles, BingoBoard.bonuses(size, tiles, lineBonus, blackoutBonus));
        return new Rerolled(withBoard(config, rebuilt, poolOf(config), unlockedInOrder(config)), objectives);
    }

    private static ObjectiveSpec objective(Tile tile) {
        return new ObjectiveSpec(tile.key(), tile.label(), "", tile.points(), Evidence.EITHER, tile.target(), 1, List.of());
    }

    private static DataObject withBoard(DataObject config, Board board, String pool, boolean inOrder) {
        DataArray keys = DataArray.empty();
        board.tiles().forEach(t -> keys.add(t.key()));
        DataObject copy = DataObject.fromJson(config.toString());
        DataObject stored = DataObject.empty().put("size", board.size()).put("seed", board.seed()).put("pool", pool).put("keys", keys);
        // a shaped board also records its shape and whether its rules about which tiles open first are enforced, so that never changes by accident later
        if (board.layout() != null) stored.put("layout", board.layout()).put("unlock", inOrder ? UNLOCK_LOCKED : UNLOCK_FREE);
        // the look is the organiser's choice and survives a re-roll, a new shape or a swapped tile
        BingoLooks.of(config).ifPresent(look -> stored.put("look", look));
        copy.put("board", stored);
        copy.put("bonuses", EventBonuses.toJson(board.bonuses()));
        return copy;
    }
}
