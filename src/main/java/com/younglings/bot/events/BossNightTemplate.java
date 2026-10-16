package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss night: a signup-led evening of one boss. Settings: {@code boss} (the boss's name, shown in the objective), {@code kills} (how many
 * kills count, 10 by default) and {@code pointsPerKill} (10). Each kill is one repeat of a single objective, recorded by an organiser or
 * claimed with proof, so the same engine gives a sign-up list, teams, a kill count and standings. Most of the value is the signup, team
 * and buy-in handling every event already has; this template just starts the objective for you.
 */
@BService
public class BossNightTemplate implements EventTemplate {
    public static final String KEY = "boss-night";
    public static final String OBJECTIVE_KEY = "boss-kill";
    public static final int DEFAULT_KILLS = 10;
    public static final long DEFAULT_POINTS_PER_KILL = 10;
    public static final int MAX_BOSS_NAME = 40;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public java.util.Set<String> fixedSettings() {
        return java.util.Set.of("boss", "kills", "pointsPerKill");
    }

    @Override
    public String label() {
        return "Boss night";
    }

    @Override
    public List<EventOptions.Option> options() {
        return List.of(
                EventOptions.Option.of("config.boss", "Boss", "The boss the night is for.", EventOptions.Step.RULES, EventOptions.Type.TEXT, null).mandatory().inQuick().maxLength(MAX_BOSS_NAME),
                EventOptions.Option.number("config.kills", "Kills that count", "Up to " + String.format("%,d", EventRules.MAX_REPEAT) + ".", EventOptions.Step.RULES, DEFAULT_KILLS, 1, EventRules.MAX_REPEAT),
                EventOptions.Option.number("config.pointsPerKill", "Points per kill", "What each kill is worth.", EventOptions.Step.RULES, DEFAULT_POINTS_PER_KILL, 0, EventRules.MAX_POINTS),
                EventOptions.reviewChannel());
    }

    @Override
    public String description() {
        return "A signup for an evening of one boss. Count kills per player or team, with proof or by an organiser, on top of the usual teams, buy-ins and prizes.";
    }

    private static long number(DataObject config, String key, long fallback) {
        try {
            return config.hasKey(key) && !config.isNull(key) ? config.getLong(key) : fallback;
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String boss(DataObject config) {
        try {
            String name = config.hasKey("boss") && !config.isNull("boss") ? config.getString("boss") : "";
            return name == null ? "" : name.strip();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<String> configProblems(DataObject config) {
        List<String> problems = new ArrayList<>();
        String boss = boss(config);
        if (boss == null) problems.add("The boss name has to be text.");
        else if (boss.length() > MAX_BOSS_NAME) problems.add("Keep the boss name to " + MAX_BOSS_NAME + " characters or fewer.");
        long kills = number(config, "kills", DEFAULT_KILLS);
        if (kills < 1 || kills > EventRules.MAX_REPEAT) problems.add("Kills that count have to be between 1 and " + EventRules.MAX_REPEAT + ".");
        long points = number(config, "pointsPerKill", DEFAULT_POINTS_PER_KILL);
        if (points < 0 || points > EventRules.MAX_POINTS) problems.add("Points per kill have to be between 0 and " + EventRules.MAX_POINTS + ".");
        return problems;
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        String boss = boss(config);
        String name = boss == null || boss.isEmpty() ? "the boss" : boss;
        return List.of(new ObjectiveSpec(OBJECTIVE_KEY, "Kill " + name, "Each kill counts. Send a screenshot of the kill, or an organiser records it.",
                number(config, "pointsPerKill", DEFAULT_POINTS_PER_KILL), Evidence.EITHER, 1, (int) number(config, "kills", DEFAULT_KILLS), List.of()));
    }
}
