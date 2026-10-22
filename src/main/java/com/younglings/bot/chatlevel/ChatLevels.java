package com.younglings.bot.chatlevel;

/** The level curve: to go from level n to n+1 takes 5n² + 50n + 100 XP (level 1 after 100 XP, level 10 after 4,550, level 50 after 247,000). Pure, so it can be tested. */
public final class ChatLevels {
    public static final int MAX_LEVEL = 200;

    private ChatLevels() {}

    /** XP needed to go from {@code level} to the next one. */
    public static long xpToNext(int level) {
        return 5L * level * level + 50L * level + 100;
    }

    /** Total XP at which {@code level} is reached (level 0 is 0 XP). */
    public static long totalFor(int level) {
        long total = 0;
        for (int l = 0; l < Math.min(level, MAX_LEVEL); l++) total += xpToNext(l);
        return total;
    }

    public static int levelOf(long xp) {
        int level = 0;
        long left = Math.max(0, xp);
        while (level < MAX_LEVEL && left >= xpToNext(level)) {
            left -= xpToNext(level);
            level++;
        }
        return level;
    }

    /** XP earned since the current level began. */
    public static long intoLevel(long xp) {
        return Math.max(0, xp) - totalFor(levelOf(xp));
    }

    /** A bar like {@code ▰▰▰▱▱▱▱▱▱▱} for the share of the current level done. */
    public static String bar(long xp, int width) {
        int level = levelOf(xp);
        if (level >= MAX_LEVEL) return "▰".repeat(width);
        int filled = (int) Math.round(width * (double) intoLevel(xp) / xpToNext(level));
        return "▰".repeat(Math.max(0, Math.min(width, filled))) + "▱".repeat(Math.max(0, width - filled));
    }
}
