package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoard.TileSpec;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules of a server's own bingo tile pool: a tile is a label, points, how many times the thing must happen, a difficulty and optional tags. Pure,
 * so every rule is testable. A server fills its pool on the dashboard (by hand, or from a starter pack made from public wiki content) and an event
 * that uses "Your tile pool" takes a copy of the enabled tiles when it is created, so editing the pool later never changes a running event.
 */
public final class BingoTilePool {
    public static final int MAX_TILES_PER_SERVER = 600;
    /** A board never needs more than this many tiles to pick from, and an event's settings hold the copy. */
    public static final int MAX_TILES_IN_EVENT = 300;
    public static final int MAX_LABEL = 100;
    public static final long MAX_POINTS = 10_000;
    public static final long MAX_TARGET = 1_000;
    public static final int MAX_TAGS = 5;
    public static final int MAX_TAG = 24;

    /** The difficulties, easiest first, with the points a new tile of that difficulty starts with. */
    public enum Difficulty {
        EASY("Easy", 10), MEDIUM("Medium", 25), HARD("Hard", 50), ELITE("Elite", 100), MASTER("Master", 150), GRANDMASTER("Grandmaster", 250);

        private final String label;
        private final long defaultPoints;

        Difficulty(String label, long defaultPoints) {
            this.label = label;
            this.defaultPoints = defaultPoints;
        }

        public String label() {
            return label;
        }

        public long defaultPoints() {
            return defaultPoints;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The difficulty named by {@code key} (any case), or null. */
        public static Difficulty parse(String key) {
            if (key == null) return null;
            try {
                return valueOf(key.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** One tile as a server keeps it. {@code tags} are lower case, in the order given. */
    public record Entry(String label, long points, long target, Difficulty difficulty, List<String> tags, boolean enabled) {
        public TileSpec spec() {
            return new TileSpec(label, points, target);
        }
    }

    private BingoTilePool() {}

    /** A label tidied up: spaces collapsed and trimmed. */
    public static String cleanLabel(String label) {
        return label == null ? "" : label.strip().replaceAll("\\s+", " ");
    }

    /** Tags tidied up: lower case, trimmed, letters digits spaces and dashes, no repeats, in order. Null if one is not valid or there are too many. */
    public static List<String> cleanTags(List<String> typed) {
        Set<String> tags = new LinkedHashSet<>();
        for (String raw : typed) {
            String tag = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (tag.isEmpty()) continue;
            if (tag.length() > MAX_TAG || !tag.matches("[a-z0-9][a-z0-9 -]*")) return null;
            tags.add(tag);
        }
        return tags.size() > MAX_TAGS ? null : new ArrayList<>(tags);
    }

    /** What is wrong with a tile as typed, in words for whoever typed it, or null. */
    public static String problem(String label, long points, long target, Difficulty difficulty) {
        String clean = cleanLabel(label);
        if (clean.isEmpty()) return "Give the tile a name.";
        if (clean.length() > MAX_LABEL) return "A tile name can be at most " + MAX_LABEL + " characters.";
        if (points < 0 || points > MAX_POINTS) return "Points go from 0 to " + MAX_POINTS + ".";
        if (target < 1 || target > MAX_TARGET) return "How many times goes from 1 to " + MAX_TARGET + ".";
        if (difficulty == null) return "Choose a difficulty from the list.";
        return null;
    }

    /** The enabled tiles that match: any of {@code difficulties} (none given = every difficulty) and every one of {@code tags}. */
    public static List<Entry> select(List<Entry> entries, Set<Difficulty> difficulties, Set<String> tags) {
        return entries.stream()
                .filter(Entry::enabled)
                .filter(e -> difficulties.isEmpty() || difficulties.contains(e.difficulty()))
                .filter(e -> e.tags().containsAll(tags))
                .collect(Collectors.toList());
    }

    /** The tiles as the event keeps them in its settings (label, points, target), the first {@link #MAX_TILES_IN_EVENT}. */
    public static DataArray toConfig(List<Entry> entries) {
        DataArray array = DataArray.empty();
        for (Entry e : entries.stream().limit(MAX_TILES_IN_EVENT).toList()) array.add(DataObject.empty().put("label", e.label()).put("points", e.points()).put("target", e.target()));
        return array;
    }

    /** What is wrong with the tiles an event holds (empty when fine). Duplicate names would make two tiles with one key, so they are refused. */
    public static List<String> configProblems(DataArray tiles) {
        List<String> problems = new ArrayList<>();
        if (tiles.length() > MAX_TILES_IN_EVENT) problems.add("A tile pool in an event can hold at most " + MAX_TILES_IN_EVENT + " tiles.");
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(tiles.length(), MAX_TILES_IN_EVENT + 1); i++) {
            if (!tiles.isType(i, net.dv8tion.jda.api.utils.data.DataType.OBJECT)) {
                problems.add("Tile " + (i + 1) + " of the pool isn't readable.");
                continue;
            }
            DataObject t = tiles.getObject(i);
            String label = cleanLabel(t.getString("label", ""));
            String problem = problem(label, t.getLong("points", -1), t.getLong("target", 0), Difficulty.EASY);
            if (problem != null) problems.add("Tile " + (i + 1) + " of the pool: " + problem);
            else if (!seen.add(label.toLowerCase(Locale.ROOT))) problems.add("The tile \"" + label + "\" is in the pool twice.");
        }
        return problems;
    }

    /** The tiles an event holds, as pool tiles. Only call with tiles that passed {@link #configProblems}. */
    public static List<TileSpec> fromConfig(DataArray tiles) {
        List<TileSpec> pool = new ArrayList<>();
        for (int i = 0; i < Math.min(tiles.length(), MAX_TILES_IN_EVENT); i++) {
            DataObject t = tiles.getObject(i);
            pool.add(new TileSpec(cleanLabel(t.getString("label", "")), t.getLong("points", 0), t.getLong("target", 1)));
        }
        return pool;
    }
}
