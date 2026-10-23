package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.plugin.PluginEventType;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which objectives of an event one thing the plugin reported counts towards. Pure, so every rule can be tested.
 * <ul>
 *   <li>A {@code DROP} counts towards an objective for that item: a bingo tile or scavenger objective whose label is the item, or
 *       {@code "Boss: item"} (when the plugin says which monster dropped it, that has to be the tile's boss too).</li>
 *   <li>A {@code KILL} counts one kill towards a boss night for that boss.</li>
 *   <li>In a bingo or scavenger hunt (T-061), the other achievement types count towards objectives labelled for them:
 *     <ul>
 *       <li>{@code CLUE}: a clue tile such as "Elite clue", "Clue scroll (hard)" or "Any clue" (a label with no tier takes any tier);</li>
 *       <li>{@code LEVEL_UP}: "99 Fletching", "Level 90 Herblore" or "Herblore 90", when the new level reaches it;</li>
 *       <li>{@code COLLECTION_LOG}: "Collection log: item" or "Log: item" (a plain item label is left to {@code DROP}, so one item never
 *           counts twice);</li>
 *       <li>{@code QUEST_COMPLETE}: "Quest: name";</li>
 *       <li>{@code ACHIEVEMENT}: "Achievement: name".</li>
 *     </ul>
 *   Each counts 1.</li>
 *   <li>{@code XP} and {@code PRESENCE} are not matched. Objectives that don't take automatic progress ({@code PROOF} or {@code ORGANISER}
 *       evidence) never match.</li>
 * </ul>
 */
public final class PluginObjectiveMatcher {
    /** Progress to record: {@code amount} towards the objective. */
    public record Hit(String objectiveKey, long amount) {}

    /** What an objective label means, as {@link BingoTiles#resolve} works it out. */
    public interface Labels extends Function<String, BingoTiles.Ref> {}

    private static final Set<String> CLUE_TIERS = Set.of("easy", "medium", "hard", "elite", "master");
    /** The words a clue tile may have besides its tier: "Clue scroll (elite)", "Complete a hard clue", "Any treasure trail". */
    private static final Pattern CLUE_FILLER = Pattern.compile("\\b(clue|clues|scroll|scrolls|treasure|trail|trails|complete|completed|an?|any|tier|casket|caskets)\\b");
    private static final Pattern LEVEL_FIRST = Pattern.compile("^(?:level\\s+)?(\\d{1,3})\\s+([a-z][a-z ]*)$");
    private static final Pattern SKILL_FIRST = Pattern.compile("^([a-z][a-z ]*?)\\s+(?:level\\s+)?(\\d{1,3})$");

    private PluginObjectiveMatcher() {}

    public static List<Hit> match(String template, String config, List<ObjectiveSpec> objectives, PluginEventType type, String sourceName, DataObject data, Labels labels) {
        List<Hit> hits = new ArrayList<>();
        if (type == PluginEventType.KILL && BossNightTemplate.KEY.equals(template)) {
            String wanted = bossOf(config);
            String killed = text(data, "bossName");
            if (!wanted.isEmpty() && wanted.equalsIgnoreCase(killed)) {
                for (ObjectiveSpec objective : objectives) {
                    if (objective.key().equals(BossNightTemplate.OBJECTIVE_KEY) && takesAutomatic(objective)) hits.add(new Hit(objective.key(), 1));
                }
            }
            return hits;
        }
        if (type == PluginEventType.DROP && !CompetitionTemplate.KEY.equals(template) && !BossNightTemplate.KEY.equals(template)) {
            String item = text(data, "itemName");
            if (item.isEmpty()) return hits;
            long quantity = Math.max(1, number(data, "quantity", 1));
            for (ObjectiveSpec objective : objectives) {
                if (!takesAutomatic(objective)) continue;
                BingoTiles.Ref ref = labels.apply(objective.label());
                boolean sameItem = item.equalsIgnoreCase(ref.item()) || item.equalsIgnoreCase(objective.label().strip());
                if (!sameItem) continue;
                // the plugin may name the monster that dropped it; a tile for another boss's drop doesn't count then
                if (ref.boss() != null && sourceName != null && !sourceName.isBlank() && !sourceName.strip().toLowerCase(Locale.ROOT).contains(ref.boss().toLowerCase(Locale.ROOT))
                        && !ref.boss().toLowerCase(Locale.ROOT).contains(sourceName.strip().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                hits.add(new Hit(objective.key(), quantity));
            }
            return hits;
        }
        if (CompetitionTemplate.KEY.equals(template) || BossNightTemplate.KEY.equals(template)) return hits;
        for (ObjectiveSpec objective : objectives) {
            if (!takesAutomatic(objective)) continue;
            String label = objective.label() == null ? "" : objective.label().strip().toLowerCase(Locale.ROOT);
            boolean counts = switch (type) {
                case CLUE -> clueTile(label, text(data, "tier"));
                case LEVEL_UP -> levelTile(label, text(data, "skill"), number(data, "level", 0));
                case COLLECTION_LOG -> named(label, text(data, "itemName"), "collection log:", "log:");
                case QUEST_COMPLETE -> named(label, text(data, "quest"), "quest:");
                case ACHIEVEMENT -> named(label, text(data, "name"), "achievement:");
                default -> false;
            };
            if (counts) hits.add(new Hit(objective.key(), 1));
        }
        return hits;
    }

    /**
     * What the bingo editor says under a tile while it is typed (T-141): whether RuneSync can tick it by itself, in words. {@code auto} is
     * true only when a report RuneSync sends today would match this label under {@link #match}'s rules; {@code text} says why or why not.
     * Null for event types that don't use tile labels (competitions and boss nights) and for an empty label.
     */
    public record Hint(boolean auto, String text) {}

    /** The event types RuneSync reports today (see the RuneSync plugin's README, "What RuneSync sends"). */
    private static final String NOT_YET = "RuneSync doesn't report %s yet, so this tile needs proof or an organiser for now.";

    public static Hint explain(String template, String rawLabel, Labels labels, Function<String, Boolean> knownItem) {
        if (CompetitionTemplate.KEY.equals(template) || BossNightTemplate.KEY.equals(template)) return null;
        String label = rawLabel == null ? "" : rawLabel.strip().toLowerCase(Locale.ROOT);
        if (label.isEmpty()) return null;
        if (label.contains("clue") || label.contains("treasure trail")) {
            String rest = CLUE_FILLER.matcher(label.replaceAll("[^a-z ]", " ")).replaceAll(" ").strip().replaceAll("\\s+", " ");
            if (rest.isEmpty()) return new Hint(true, "Auto with RuneSync: any clue scroll the member completes (read from their game messages).");
            if (CLUE_TIERS.contains(rest)) return new Hint(true, "Auto with RuneSync: a " + rest + " clue scroll the member completes.");
            return new Hint(false, "Looks like a clue tile, but only one tier (easy, medium, hard, elite, master) or none can be in the name.");
        }
        if (LEVEL_FIRST.matcher(label).matches() || SKILL_FIRST.matcher(label).matches()) return new Hint(false, String.format(NOT_YET, "level-ups"));
        if (label.startsWith("collection log:") || label.startsWith("log:")) return new Hint(false, String.format(NOT_YET, "collection log entries"));
        if (label.startsWith("quest:")) return new Hint(false, String.format(NOT_YET, "quests"));
        if (label.startsWith("achievement:")) return new Hint(false, String.format(NOT_YET, "achievements"));
        BingoTiles.Ref ref = labels.apply(rawLabel.strip());
        String item = ref.item() == null ? rawLabel.strip() : ref.item();
        if (Boolean.TRUE.equals(knownItem.apply(item))) {
            String from = ref.boss() == null ? "" : " from " + ref.boss();
            return new Hint(true, "Auto with RuneSync: a drop of " + item + from + " (loot from an NPC the member kills).");
        }
        return new Hint(false, "Not an item the bot knows. RuneSync ticks it only if a drop's name is exactly \"" + item
                + "\". Pick the boss and item from the lists, or write \"Boss: item\".");
    }

    /** A clue tile for this tier: only clue words and at most one tier are left in the label, and that tier (if any) is the one reported. */
    static boolean clueTile(String label, String tier) {
        if (!label.contains("clue") && !label.contains("treasure trail")) return false;
        String rest = CLUE_FILLER.matcher(label.replaceAll("[^a-z ]", " ")).replaceAll(" ").strip().replaceAll("\\s+", " ");
        if (rest.isEmpty()) return true; // "Any clue": every tier
        return CLUE_TIERS.contains(rest) && rest.equalsIgnoreCase(tier.strip());
    }

    /** A level tile ("99 fletching", "level 90 herblore", "herblore 90") the reported level of that skill reaches. */
    static boolean levelTile(String label, String skill, long level) {
        if (skill.isEmpty() || level <= 0) return false;
        Matcher first = LEVEL_FIRST.matcher(label);
        Matcher second = SKILL_FIRST.matcher(label);
        String wantedSkill;
        long wantedLevel;
        if (first.matches()) {
            wantedLevel = Long.parseLong(first.group(1));
            wantedSkill = first.group(2);
        } else if (second.matches()) {
            wantedSkill = second.group(1);
            wantedLevel = Long.parseLong(second.group(2));
        } else {
            return false;
        }
        return wantedSkill.strip().equalsIgnoreCase(skill.strip()) && level >= wantedLevel;
    }

    /** "prefix name" for one of the prefixes, where name is what was reported. */
    private static boolean named(String label, String reported, String... prefixes) {
        if (reported.isEmpty()) return false;
        for (String prefix : prefixes) {
            if (label.startsWith(prefix) && label.substring(prefix.length()).strip().equalsIgnoreCase(reported.strip())) return true;
        }
        return false;
    }

    private static boolean takesAutomatic(ObjectiveSpec objective) {
        Evidence evidence = objective.evidence();
        return EventRules.evidenceAllows(evidence, Source.AUTO) && evidence != Evidence.ORGANISER;
    }

    private static String bossOf(String config) {
        try {
            DataObject json = DataObject.fromJson(config == null || config.isBlank() ? "{}" : config);
            return json.hasKey("boss") && !json.isNull("boss") ? json.getString("boss").strip() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String text(DataObject data, String key) {
        return data.hasKey(key) && !data.isNull(key) && data.opt(key).orElse(null) instanceof String s ? s.strip() : "";
    }

    private static long number(DataObject data, String key, long fallback) {
        return data.hasKey(key) && !data.isNull(key) && data.opt(key).orElse(null) instanceof Number n ? n.longValue() : fallback;
    }
}
