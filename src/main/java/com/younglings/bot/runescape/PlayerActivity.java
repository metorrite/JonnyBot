package com.younglings.bot.runescape;

/**
 * One entry from RuneMetrics' "recent activities" feed (quest completions, level-ups, boss kills,
 * etc). {@code date} is kept exactly as RuneScape's API sends it (e.g. "23-Sep-2026 23:30") rather
 * than parsed into a timestamp — see {@code RuneScapeDatabaseInitializer} for why.
 */
public record PlayerActivity(String date, String text, String details) {
}
