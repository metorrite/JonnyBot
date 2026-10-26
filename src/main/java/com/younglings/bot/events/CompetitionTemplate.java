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
 * <p>
 * {@code xpSource} says where the gains come from: {@value #SOURCE_RUNEMETRICS} (the default, the polling above) or {@value #SOURCE_PLUGIN}, the
 * RuneClans hub's XP ledger fed by RuneSync ({@link PluginXpTracker}, T-537). The plugin choice is offered only while the bot is connected to the hub.
 */
@BService
public class CompetitionTemplate implements EventTemplate {
    public static final String KEY = "competition";
    public static final String OBJECTIVE_KEY = "xp-gained";
    public static final int OVERALL = -1;
    public static final long DEFAULT_XP_PER_POINT = 10_000;
    public static final String SOURCE_RUNEMETRICS = "runemetrics";
    public static final String SOURCE_PLUGIN = "plugin";

    /** Whether the plugin source can be offered: set once at startup by {@link PluginXpTracker} when the bot talks to the hub. */
    private static volatile boolean pluginSourceOffered;

    static void offerPluginSource(boolean offered) {
        pluginSourceOffered = offered;
    }

    public static boolean pluginSourceOffered() {
        return pluginSourceOffered;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public java.util.Set<String> fixedSettings() {
        return java.util.Set.of("skillId", "xpPerPoint", "xpSource", "confirmedLinksOnly");
    }

    @Override
    public String label() {
        return "Competition";
    }

    /** XP gained is read from the player's RuneScape name. */
    @Override
    public boolean needsRsn() {
        return true;
    }

    @Override
    public List<EventOptions.Option> options() {
        List<EventOptions.Choice> skills = new ArrayList<>();
        skills.add(new EventOptions.Choice(String.valueOf(OVERALL), "Overall (total XP)"));
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) skills.add(new EventOptions.Choice(String.valueOf(id), RuneScapeSkillCatalog.nameFor(id)));
        List<EventOptions.Option> list = new ArrayList<>(List.of(
                EventOptions.Option.choice("config.skillId", "Skill", "Whose XP counts: one skill, or total XP.", EventOptions.Step.RULES, (long) OVERALL, skills).numericChoices().widget("skill").inQuick(),
                EventOptions.Option.number("config.xpPerPoint", "XP per point", "How much XP earns one point. A larger number keeps points small.", EventOptions.Step.RULES,
                        DEFAULT_XP_PER_POINT, 1, EventRules.MAX_TARGET)));
        if (pluginSourceOffered) {
            list.add(EventOptions.Option.choice("config.xpSource", "Where XP comes from", "RuneMetrics reads public profiles every few minutes. RuneSync counts within a minute or two, private profiles too, but only "
                            + "players who run the RuneSync plugin and share XP with this bot; the others score nothing. It can't be changed once the event exists.", EventOptions.Step.RULES, SOURCE_RUNEMETRICS,
                    List.of(new EventOptions.Choice(SOURCE_RUNEMETRICS, "RuneMetrics (public profiles)"), new EventOptions.Choice(SOURCE_PLUGIN, "RuneSync plugin"))));
            list.add(EventOptions.Option.of("config.confirmedLinksOnly", "Count confirmed links only", "Only players whose RS Link the RuneClans hub has confirmed from "
                            + "public data score. The others are listed as not confirmed yet. It can't be changed once the event exists.", EventOptions.Step.RULES,
                    EventOptions.Type.BOOL, false).showWhen(EventOptions.When.is("config.xpSource", SOURCE_PLUGIN)));
        }
        return list;
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

    /** Where a competition's gains come from: {@link #SOURCE_PLUGIN} only when it says so, else {@link #SOURCE_RUNEMETRICS} (every event made before T-537). */
    public static String xpSourceOf(DataObject config) {
        return config.hasKey("xpSource") && !config.isNull("xpSource") && SOURCE_PLUGIN.equals(String.valueOf(config.get("xpSource"))) ? SOURCE_PLUGIN : SOURCE_RUNEMETRICS;
    }

    public static boolean countsFromPlugin(DataObject config) {
        return SOURCE_PLUGIN.equals(xpSourceOf(config));
    }

    /** A RuneSync competition that counts only players whose RS Link the hub confirmed (T-552). Off unless it says so. */
    public static boolean confirmedLinksOnly(DataObject config) {
        return countsFromPlugin(config) && config.hasKey("confirmedLinksOnly") && !config.isNull("confirmedLinksOnly")
                && "true".equalsIgnoreCase(String.valueOf(config.get("confirmedLinksOnly")));
    }

    public static boolean validSource(String source) {
        return SOURCE_RUNEMETRICS.equals(source) || SOURCE_PLUGIN.equals(source);
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
        if (config.hasKey("xpSource") && !config.isNull("xpSource") && !validSource(String.valueOf(config.get("xpSource")))) {
            problems.add("XP comes from RuneMetrics or the RuneSync plugin.");
        }
        return problems;
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        int skill = skillOf(config);
        long per = xpPerPoint(config);
        String name = skillName(skill);
        String from = countsFromPlugin(config) ? " Counted from RuneSync." : "";
        return List.of(new ObjectiveSpec(OBJECTIVE_KEY, name + " XP gained", "One point for every " + String.format("%,d", per) + " XP gained while the event runs." + from,
                1, Evidence.AUTO, per, EventRules.MAX_REPEAT, List.of()));
    }
}
