package com.runeclans.bot.events;

import com.runeclans.bot.runescape.SkillValue;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** The pure arithmetic of reading a competition from stored snapshots, kept apart from the sweep so it can be tested on its own. */
public final class CompetitionTracking {
    private CompetitionTracking() {}

    /** A snapshot's XP for the competition's skill: the skill's own XP, or the total for {@link CompetitionTemplate#OVERALL}. */
    public static OptionalLong xpOf(int skillId, long totalXp, List<SkillValue> skills) {
        if (skillId == CompetitionTemplate.OVERALL) return OptionalLong.of(totalXp);
        for (SkillValue skill : skills) if (skill.skillId() == skillId) return OptionalLong.of(skill.xp());
        return OptionalLong.empty();
    }

    /**
     * How much more XP to add to the event for a player: what they have gained since their baseline, minus what is already counted. Never
     * negative (a lower reading than before, from a data glitch or a reset, adds nothing and takes nothing back).
     */
    public static long toRecord(long baseline, long current, long alreadyCounted) {
        return Math.max(0, (current - baseline) - alreadyCounted);
    }

    /** When a player's RuneSync gains start counting in a competition: the later of the event's start and when they joined. */
    public static Instant countsFrom(Instant eventStarted, Instant joinedAt) {
        return joinedAt != null && joinedAt.isAfter(eventStarted) ? joinedAt : eventStarted;
    }

    /** The progress row's key for one pass of {@link PluginXpTracker}: one per player per minute, so a pass that runs twice can't count twice. */
    public static String pluginSourceKey(long userId, Instant at) {
        return "pxp:" + userId + ":" + at.getEpochSecond() / 60;
    }

    /** Who counts a player in an auto competition (T-557). */
    public enum Counter { RUNESYNC, RUNEMETRICS }

    /**
     * In an auto competition, which tracker owns each player: whichever recorded their first tracked progress ({@code pxp:} keys are
     * {@link PluginXpTracker}'s, {@code xp:} keys {@link EventTracker}'s). A player nobody has counted yet is absent. Owning keeps a player from
     * being counted twice when the hub starts or stops counting them mid-event.
     */
    public static Map<Long, Counter> owners(List<EventRepository.Progress> progress) {
        Map<Long, Counter> owners = new HashMap<>();
        progress.stream()
                .filter(p -> p.source() == EventRules.Source.IMPORT && CompetitionTemplate.OBJECTIVE_KEY.equals(p.objectiveKey()) && p.sourceKey() != null)
                .sorted(Comparator.comparing(EventRepository.Progress::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(EventRepository.Progress::id))
                .forEach(p -> {
                    Counter c = p.sourceKey().startsWith("pxp:") ? Counter.RUNESYNC : p.sourceKey().startsWith("xp:") ? Counter.RUNEMETRICS : null;
                    if (c != null) owners.putIfAbsent(p.userId(), c);
                });
        return owners;
    }

    /** RuneMetrics and RuneSync may differ by this much XP before it is flagged (RuneMetrics lags a few minutes and starts at its first reading). */
    public static final long DISAGREE_MIN_XP = 100_000;
    /** ...or by this share of the larger number, whichever is more. */
    public static final double DISAGREE_SHARE = 0.2;

    /** Whether RuneMetrics' gain for a RuneSync-counted player disagrees enough with RuneSync's to show an organiser (never corrected on its own). */
    public static boolean disagrees(long runeSync, long runeMetrics) {
        long diff = Math.abs(runeSync - runeMetrics);
        return diff > Math.max(DISAGREE_MIN_XP, (long) (Math.max(runeSync, runeMetrics) * DISAGREE_SHARE));
    }
}
