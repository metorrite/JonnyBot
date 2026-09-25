package com.younglings.bot.runescape;

import java.util.List;

/**
 * The "Overall" line plus the 29 individual skill lines from the classic hiscores CSV — see
 * {@link RuneScapeApiClient#fetchHiscoresOverall}. {@code skills} is empty if the response didn't
 * contain the expected number of lines (parsed defensively; the aggregate line is still usable
 * either way).
 */
public record HiscoresOverall(long rank, int totalLevel, long totalXp, List<SkillValue> skills) {
}
