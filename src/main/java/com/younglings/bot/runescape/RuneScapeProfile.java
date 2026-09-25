package com.younglings.bot.runescape;

import java.util.List;

/** A snapshot of a player's public RuneMetrics profile — see {@link RuneScapeApiClient#fetchProfile}. */
public record RuneScapeProfile(String name, int totalLevel, long totalXp, int combatLevel,
                                int questsComplete, int questsStarted, int questsNotStarted,
                                List<SkillValue> skills, List<PlayerActivity> activities) {
}
