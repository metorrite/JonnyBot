package com.younglings.bot.events;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bonuses for completing a whole group of objectives: a bingo row, column or diagonal, a blackout, a tier of a scavenger hunt. A bonus is a
 * name, the objectives it needs (each completed at least once) and the points it pays. They live in the event's settings under
 * {@code bonuses}, so any template can use them. Pure, so it can be tested.
 */
public final class EventBonuses {
    public static final int MAX_BONUSES = 60;
    public static final int MAX_NAME = 40;
    public static final long MAX_POINTS = 100_000;

    public record Bonus(String name, List<String> keys, long points) {}

    private EventBonuses() {}

    /** The bonuses in {@code config}; anything unreadable is skipped (see {@link #problems}). */
    public static List<Bonus> parse(DataObject config) {
        List<Bonus> bonuses = new ArrayList<>();
        if (config == null || !config.hasKey("bonuses") || config.isNull("bonuses")) return bonuses;
        try {
            DataArray array = config.getArray("bonuses");
            for (int i = 0; i < array.length(); i++) {
                DataObject b = array.getObject(i);
                List<String> keys = new ArrayList<>();
                if (b.hasKey("keys") && !b.isNull("keys")) {
                    DataArray k = b.getArray("keys");
                    for (int j = 0; j < k.length(); j++) keys.add(k.getString(j));
                }
                bonuses.add(new Bonus(b.getString("name", "").strip(), keys, b.getLong("points", 0)));
            }
        } catch (RuntimeException e) {
            return bonuses;
        }
        return bonuses;
    }

    public static DataArray toJson(List<Bonus> bonuses) {
        DataArray array = DataArray.empty();
        for (Bonus b : bonuses) {
            DataArray keys = DataArray.empty();
            b.keys().forEach(keys::add);
            array.add(DataObject.empty().put("name", b.name()).put("keys", keys).put("points", b.points()));
        }
        return array;
    }

    /** What is wrong with the bonuses in {@code config}, given the objectives that exist. */
    public static List<String> problems(DataObject config, Set<String> objectiveKeys) {
        List<String> problems = new ArrayList<>();
        if (config == null || !config.hasKey("bonuses") || config.isNull("bonuses")) return problems;
        List<Bonus> bonuses;
        try {
            if (config.getArray("bonuses").length() > MAX_BONUSES) problems.add("An event can have at most " + MAX_BONUSES + " bonuses.");
            bonuses = parse(config);
        } catch (RuntimeException e) {
            problems.add("The bonuses couldn't be read.");
            return problems;
        }
        for (Bonus b : bonuses) {
            String name = b.name().isEmpty() ? "A bonus" : "\"" + b.name() + "\"";
            if (b.name().isEmpty() || b.name().length() > MAX_NAME) problems.add("Every bonus needs a name of up to " + MAX_NAME + " characters.");
            if (b.points() < 0 || b.points() > MAX_POINTS) problems.add(name + ": points have to be between 0 and " + MAX_POINTS + ".");
            if (b.keys().isEmpty()) problems.add(name + ": it needs at least one objective.");
            for (String key : b.keys()) if (!objectiveKeys.contains(key)) problems.add(name + ": \"" + key + "\" isn't an objective of this event.");
            if (b.keys().size() != Set.copyOf(b.keys()).size()) problems.add(name + ": an objective is listed twice.");
        }
        return problems.stream().distinct().toList();
    }

    /** The bonuses earned by a subject whose objective completions are {@code completions}: every objective in the group done at least once. */
    public static List<Bonus> earned(List<Bonus> bonuses, Map<String, Integer> completions) {
        return bonuses.stream().filter(b -> !b.keys().isEmpty() && b.keys().stream().allMatch(k -> completions.getOrDefault(k, 0) >= 1)).toList();
    }
}
