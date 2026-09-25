package com.younglings.bot.runescape;

/**
 * Minimum XP required for each level, 1-120 — game mechanics, not something derived from live API
 * data. Sourced from the RuneScape Wiki's Experience/Table page (index 0 = level 1 ... index 119 =
 * level 120). Invention (skill ID 26 in {@link RuneScapeSkillCatalog}) is an elite skill on a
 * different curve — confirmed on the same wiki page ("This section discusses regular skills: elite
 * skills use a different formula") — and gets its own table; every other skill uses the standard one,
 * levels 100-120 included (the wiki states the same formula that produces 13,034,431 for level 99
 * continues unmodified through level 120 for regular skills).
 */
public final class RuneScapeXpTable {
    private static final int INVENTION_SKILL_ID = 26;

    private static final long[] STANDARD = {
            0, 83, 174, 276, 388, 512, 650, 801, 969, 1154,
            1358, 1584, 1833, 2107, 2411, 2746, 3115, 3523, 3973, 4470,
            5018, 5624, 6291, 7028, 7842, 8740, 9730, 10824, 12031, 13363,
            14833, 16456, 18247, 20224, 22406, 24815, 27473, 30408, 33648, 37224,
            41171, 45529, 50339, 55649, 61512, 67983, 75127, 83014, 91721, 101333,
            111945, 123660, 136594, 150872, 166636, 184040, 203254, 224466, 247886, 273742,
            302288, 333804, 368599, 407015, 449428, 496254, 547953, 605032, 668051, 737627,
            814445, 899257, 992895, 1096278, 1210421, 1336443, 1475581, 1629200, 1798808, 1986068,
            2192818, 2421087, 2673114, 2951373, 3258594, 3597792, 3972294, 4385776, 4842295, 5346332,
            5902831, 6517253, 7195629, 7944614, 8771558, 9684577, 10692629, 11805606, 13034431, 14391160,
            15889109, 17542976, 19368992, 21385073, 23611006, 26068632, 28782069, 31777943, 35085654, 38737661,
            42769801, 47221641, 52136869, 57563718, 63555443, 70170840, 77474828, 85539082, 94442737, 104273167
    };

    private static final long[] INVENTION = {
            0, 830, 1861, 2902, 3980, 5126, 6380, 7787, 9400, 11275,
            13605, 16372, 19656, 23546, 28134, 33520, 39809, 47109, 55535, 65209,
            77190, 90811, 106221, 123573, 143025, 164742, 188893, 215651, 245196, 277713,
            316311, 358547, 404634, 454796, 509259, 568254, 632019, 700797, 774834, 854383,
            946227, 1044569, 1149696, 1261903, 1381488, 1508756, 1644015, 1787581, 1939773, 2100917,
            2283490, 2476369, 2679917, 2894505, 3120508, 3358307, 3608290, 3870846, 4146374, 4435275,
            4758122, 5096111, 5449685, 5819299, 6205407, 6608473, 7028964, 7467354, 7924122, 8399751,
            8925664, 9472665, 10041285, 10632061, 11245538, 11882262, 12542789, 13227679, 13937496, 14672812,
            15478994, 16313404, 17176661, 18069395, 18992239, 19945833, 20930821, 21947856, 22997593, 24080695,
            25259906, 26475754, 27728955, 29020233, 30350318, 31719944, 33129852, 34580790, 36073511, 37608773,
            39270442, 40978509, 42733789, 44537107, 46389292, 48291180, 50243611, 52247435, 54303504, 56412678,
            58575824, 60793812, 63067521, 65397835, 67785643, 70231841, 72737330, 75303019, 77929820, 80618654
    };

    private RuneScapeXpTable() {}

    /** Minimum XP needed for {@code level} (clamped to 1-120) in the given skill. */
    public static long xpForLevel(int skillId, int level) {
        long[] table = skillId == INVENTION_SKILL_ID ? INVENTION : STANDARD;
        int index = Math.max(1, Math.min(level, 120)) - 1;
        return table[index];
    }

    /** XP still needed to reach {@code currentLevel + 1}, or 0 if already at level 120. */
    public static long xpToNextLevel(int skillId, int currentLevel, long currentXp) {
        if (currentLevel >= 120) return 0;
        return Math.max(0, xpForLevel(skillId, currentLevel + 1) - currentXp);
    }
}
