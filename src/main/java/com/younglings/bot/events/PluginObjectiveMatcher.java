package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.plugin.PluginEventType;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Decides which objectives of an event one thing the plugin reported counts towards. Pure, so every rule can be tested.
 * <ul>
 *   <li>A {@code DROP} counts towards an objective for that item: a bingo tile or scavenger objective whose label is the item, or
 *       {@code "Boss: item"} (when the plugin says which monster dropped it, that has to be the tile's boss too).</li>
 *   <li>A {@code KILL} counts one kill towards a boss night for that boss.</li>
 *   <li>Everything else is not matched yet. Objectives that don't take automatic progress ({@code PROOF} or {@code ORGANISER} evidence) never match.</li>
 * </ul>
 */
public final class PluginObjectiveMatcher {
    /** Progress to record: {@code amount} towards the objective. */
    public record Hit(String objectiveKey, long amount) {}

    /** What an objective label means, as {@link BingoTiles#resolve} works it out. */
    public interface Labels extends Function<String, BingoTiles.Ref> {}

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
        }
        return hits;
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
