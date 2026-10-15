package com.younglings.bot.events;

import com.younglings.bot.events.EventBonuses.Bonus;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Scavenger hunt: a list of things to find, each completed by sending proof. Settings: {@code steps} (3 to 30, 8 by default),
 * {@code stepPoints} (25), {@code ordered} (false: every item open from the start; true: each unlocks after the one before it) and
 * {@code finishBonus} (100, for finding everything). The steps are ordinary objectives, so an organiser renames them into real clues,
 * changes points, or adds more on the Objectives tab.
 */
@BService
public class ScavengerHuntTemplate implements EventTemplate {
    public static final String KEY = "scavenger-hunt";
    public static final int MIN_STEPS = 3;
    public static final int MAX_STEPS = 30;
    public static final int DEFAULT_STEPS = 8;
    public static final long DEFAULT_STEP_POINTS = 25;
    public static final long DEFAULT_FINISH_BONUS = 100;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public java.util.Set<String> fixedSettings() {
        return java.util.Set.of("steps", "stepPoints", "ordered", "finishBonus", "bonuses");
    }

    @Override
    public String label() {
        return "Scavenger hunt";
    }

    @Override
    public String description() {
        return "A list of things to find. Players send a screenshot for each, in any order or one after another, and earn a bonus for finding everything.";
    }

    private static long number(DataObject config, String key, long fallback) {
        try {
            return config.hasKey(key) && !config.isNull(key) ? config.getLong(key) : fallback;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean ordered(DataObject config) {
        try {
            return config.hasKey("ordered") && !config.isNull("ordered") && config.getBoolean("ordered");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<String> configProblems(DataObject config) {
        List<String> problems = new ArrayList<>();
        long steps = number(config, "steps", DEFAULT_STEPS);
        if (steps < MIN_STEPS || steps > MAX_STEPS) problems.add("A scavenger hunt needs between " + MIN_STEPS + " and " + MAX_STEPS + " items.");
        long points = number(config, "stepPoints", DEFAULT_STEP_POINTS);
        if (points < 0 || points > EventRules.MAX_POINTS) problems.add("Points per item have to be between 0 and " + EventRules.MAX_POINTS + ".");
        long bonus = number(config, "finishBonus", DEFAULT_FINISH_BONUS);
        if (bonus < 0 || bonus > EventRules.MAX_POINTS) problems.add("The finish bonus has to be between 0 and " + EventRules.MAX_POINTS + ".");
        return problems;
    }

    /** The finish bonus is stored with the event so it shows and scores like any other bonus. */
    @Override
    public DataObject prepare(DataObject config) {
        DataObject copy = DataObject.fromJson(config.toString());
        long bonus = number(config, "finishBonus", DEFAULT_FINISH_BONUS);
        List<ObjectiveSpec> steps = starterObjectives(config);
        copy.put("bonuses", EventBonuses.toJson(bonus > 0 ? List.of(new Bonus("Found everything", steps.stream().map(ObjectiveSpec::key).toList(), bonus)) : List.of()));
        return copy;
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        int steps = (int) number(config, "steps", DEFAULT_STEPS);
        long points = number(config, "stepPoints", DEFAULT_STEP_POINTS);
        boolean ordered = ordered(config);
        List<ObjectiveSpec> list = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            List<String> needs = ordered && i > 1 ? List.of("item-" + (i - 1)) : List.of();
            list.add(new ObjectiveSpec("item-" + i, "Item " + i, "Send a screenshot to prove it.", points, Evidence.PROOF, 1, 1, needs));
        }
        return list;
    }
}
