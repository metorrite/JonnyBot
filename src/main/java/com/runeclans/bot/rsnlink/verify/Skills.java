package com.runeclans.bot.rsnlink.verify;

/**
 * The skill numbering everything here uses: the plugin API's {@code SkillType} ids (0 Attack .. 28 Necromancy), which are also RuneMetrics'
 * {@code skillvalues[].id}. Hiscores lite has one more line at the top (Overall), so skill {@code id} is on line {@code id + 1}.
 */
public final class Skills {
    private Skills() {}

    /** Attack (0) to Necromancy (28). */
    public static final int COUNT = 29;

    /** 200 million XP, the cap, in tenths: a skill there never changes again, so it can't be a prediction. */
    public static final long MAX_XP10 = 2_000_000_000L;

    /** What a public source shows for a skill it doesn't rank (under level 15, outside the top 2 million, or a 404 player). */
    public static final long UNRANKED = -1;

    private static final String[] NAMES = {"Attack", "Defence", "Strength", "Constitution", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
            "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming",
            "Runecrafting", "Hunter", "Construction", "Summoning", "Dungeoneering", "Divination", "Invention", "Archaeology", "Necromancy"};

    public static boolean valid(int id) {
        return id >= 0 && id < COUNT;
    }

    public static String name(int id) {
        return valid(id) ? NAMES[id] : "Skill " + id;
    }

    /** Whole XP as Hiscores shows it for an exact value in tenths. */
    public static long wholeXp(long xp10) {
        return Math.floorDiv(xp10, 10);
    }
}
