package com.younglings.bot.events;

import com.younglings.bot.runescape.SkillValue;

import java.util.List;
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
}
