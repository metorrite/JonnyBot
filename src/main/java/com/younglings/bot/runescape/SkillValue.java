package com.younglings.bot.runescape;

/** One skill's standing, from either RuneMetrics or the classic hiscores. See {@link RuneScapeSkillCatalog} for what {@code skillId} maps to. */
public record SkillValue(int skillId, int level, long xp, int rank) {
}
