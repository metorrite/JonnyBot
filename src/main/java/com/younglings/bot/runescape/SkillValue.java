package com.younglings.bot.runescape;

/** One skill's standing from a RuneMetrics profile. {@code skillId} 0-25 are the trainable skills (Invention is 26+). */
public record SkillValue(int skillId, int level, long xp, int rank) {
}
