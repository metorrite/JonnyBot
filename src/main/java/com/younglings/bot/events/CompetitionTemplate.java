package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Competition: who gains the most XP in one skill (or overall) while the event runs. The bot reads each player's linked account from the
 * normal polling and adds their gain to the event as it grows ({@link EventTracker}); an organiser can still adjust anyone by hand.
 * Settings: {@code skillId} (a skill from {@link RuneScapeSkillCatalog}, or {@code -1} for total XP, the default) and {@code xpPerPoint}
 * (how much XP earns one point, 10,000 by default). Points are the XP gained divided by that, so a larger number keeps points small.
 */
@BService
public class CompetitionTemplate implements EventTemplate {
    public static final String KEY = "competition";
    public static final String OBJECTIVE_KEY = "xp-gained";
    public static final int OVERALL = -1;
    public static final long DEFAULT_XP_PER_POINT = 10_000;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public java.util.Set<String> fixedSettings() {
        return java.util.Set.of("skillId", "xpPerPoint");
    }

    @Override
    public String label() {
        return "Competition";
    }

    @Override
    public List<EventOptions.Option> options() {
        List<EventOptions.Choice> skills = new ArrayList<>();
        skills.add(new EventOptions.Choice(String.valueOf(OVERALL), "Overall (total XP)"));
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) skills.add(new EventOptions.Choice(String.valueOf(id), RuneScapeSkillCatalog.nameFor(id)));
        return List.of(
                EventOptions.Option.choice("config.skillId", "Skill", "Whose XP counts: one skill, or total XP.", EventOptions.Step.RULES, (long) OVERALL, skills).numericChoices().widget("skill").inQuick(),
                EventOptions.Option.number("config.xpPerPoint", "XP per point", "How much XP earns one point. A larger number keeps points small.", EventOptions.Step.RULES,
                        DEFAULT_XP_PER_POINT, 1, EventRules.MAX_TARGET));
    }

    @Override
    public String description() {
        return "Who gains the most XP in a skill (or overall) while the event runs. Progress comes from players' linked RuneScape accounts, so nobody has to send screenshots.";
    }

    private static long number(DataObject config, String key, long fallback) {
        try {
            return config.hasKey(key) && !config.isNull(key) ? config.getLong(key) : fallback;
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    /** The skill a competition is about: {@link #OVERALL} or a skill id. */
    public static int skillOf(DataObject config) {
        long id = number(config, "skillId", OVERALL);
        return id >= Integer.MIN_VALUE && id <= Integer.MAX_VALUE ? (int) id : Integer.MIN_VALUE;
    }

    public static long xpPerPoint(DataObject config) {
        return number(config, "xpPerPoint", DEFAULT_XP_PER_POINT);
    }

    public static String skillName(int skillId) {
        return skillId == OVERALL ? "Overall" : RuneScapeSkillCatalog.nameFor(skillId);
    }

    @Override
    public List<String> configProblems(DataObject config) {
        List<String> problems = new ArrayList<>();
        int skill = skillOf(config);
        if (skill != OVERALL && (skill < 0 || skill >= RuneScapeSkillCatalog.skillCount())) problems.add("Choose a skill from the list, or overall XP.");
        long per = xpPerPoint(config);
        if (per < 1 || per > EventRules.MAX_TARGET) problems.add("XP per point has to be between 1 and " + EventRules.MAX_TARGET + ".");
        return problems;
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        int skill = skillOf(config);
        long per = xpPerPoint(config);
        String name = skillName(skill);
        return List.of(new ObjectiveSpec(OBJECTIVE_KEY, name + " XP gained", "One point for every " + String.format("%,d", per) + " XP gained while the event runs.",
                1, Evidence.AUTO, per, EventRules.MAX_REPEAT, List.of()));
    }
}
