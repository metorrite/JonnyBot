package com.younglings.bot.runescape;

/** The "Overall" line from the classic hiscores CSV — see {@link RuneScapeApiClient#fetchHiscoresOverall}. */
public record HiscoresOverall(long rank, int totalLevel, long totalXp) {
}
