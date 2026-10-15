package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.ObjectiveSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the progress log into standings. Nothing here is stored: the log is the truth, and anyone can recompute the same answer from it.
 * An objective completes once its amounts add up to the target, and counts again for every further multiple of the target up to its repeat
 * limit; points are earned per completion. Pure, so it can be tested.
 */
public final class EventScoring {
    /** One entry of the progress log, for one subject (a player, or a team when the event scores by team). Negative amounts reverse earlier ones. */
    public record Entry(String subject, String objectiveKey, long amount, Instant at) {}

    /** One subject's result. {@code completions} counts per objective key; {@code lastCompletedAt} breaks ties (earlier wins). */
    public record Standing(String subject, long points, int objectivesCompleted, Map<String, Integer> completions, Instant lastCompletedAt) {}

    private EventScoring() {}

    /** How many times an objective has counted for something that has made {@code total} progress on it. */
    public static int completions(ObjectiveSpec objective, long total) {
        if (total <= 0) return 0;
        long times = total / objective.target();
        return (int) Math.min(times, objective.repeatLimit());
    }

    /** Standings for every subject that has any progress, best first (most points, then who finished earlier, then the subject key for a stable order). */
    public static List<Standing> standings(List<ObjectiveSpec> objectives, List<Entry> log) {
        return standings(objectives, log, List.of());
    }

    /** The same, with a bonus added for every group of objectives a subject has completed in full. */
    public static List<Standing> standings(List<ObjectiveSpec> objectives, List<Entry> log, List<EventBonuses.Bonus> bonuses) {
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);

        List<Entry> ordered = new ArrayList<>(log);
        ordered.sort(Comparator.comparing(Entry::at));

        Map<String, Map<String, Long>> totals = new LinkedHashMap<>();
        Map<String, Instant> lastAt = new HashMap<>();
        for (Entry e : ordered) {
            ObjectiveSpec o = byKey.get(e.objectiveKey());
            if (o == null) continue; // an objective removed after progress was logged simply stops counting
            Map<String, Long> mine = totals.computeIfAbsent(e.subject(), s -> new HashMap<>());
            long before = mine.getOrDefault(o.key(), 0L);
            long after = Math.max(0, before + e.amount());
            mine.put(o.key(), after);
            if (completions(o, after) > completions(o, before)) lastAt.put(e.subject(), e.at());
        }

        List<Standing> standings = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            Map<String, Integer> completions = new LinkedHashMap<>();
            long points = 0;
            int completed = 0;
            for (ObjectiveSpec o : objectives) {
                int times = completions(o, entry.getValue().getOrDefault(o.key(), 0L));
                if (times == 0) continue;
                completions.put(o.key(), times);
                points += o.points() * times;
                completed++;
            }
            for (EventBonuses.Bonus bonus : EventBonuses.earned(bonuses, completions)) points += bonus.points();
            standings.add(new Standing(entry.getKey(), points, completed, completions, lastAt.get(entry.getKey())));
        }
        standings.sort(Comparator.comparingLong(Standing::points).reversed()
                .thenComparing(s -> s.lastCompletedAt() == null ? Instant.MAX : s.lastCompletedAt())
                .thenComparing(Standing::subject));
        return standings;
    }

    /** Whether an objective is open to a subject: every prerequisite has completed at least once for them. */
    public static boolean available(ObjectiveSpec objective, Map<String, Long> subjectTotals, Map<String, ObjectiveSpec> byKey) {
        for (String prerequisite : objective.prerequisites()) {
            ObjectiveSpec p = byKey.get(prerequisite);
            if (p == null) return false;
            if (completions(p, subjectTotals.getOrDefault(prerequisite, 0L)) < 1) return false;
        }
        return true;
    }
}
