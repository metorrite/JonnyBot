package com.younglings.bot.runescape;

import java.util.List;

/**
 * Maps RuneMetrics' numeric skill IDs (the {@code id} field in {@code skillvalues}) to their
 * names, in the game's own skill order. Not documented anywhere official — verified live by
 * cross-referencing a real account's {@code skillvalues} ranks against the classic hiscores CSV
 * (whose line order for skills 1-29, after the "Overall" line, is well-established), matching all
 * 29 skills exactly by rank. Jagex only ever appends new skills at the end (Invention, Archaeology,
 * Necromancy were each added this way), so this list only needs a new entry, never reordering, if
 * another skill is ever released.
 */
public final class RuneScapeSkillCatalog {
    private static final List<String> NAMES = List.of(
            "Attack", "Defence", "Strength", "Constitution", "Ranged", "Prayer", "Magic",
            "Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing",
            "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecrafting",
            "Hunter", "Construction", "Summoning", "Dungeoneering", "Divination", "Invention",
            "Archaeology", "Necromancy"
    );

    /** The game's own skill-tab groupings — not live-verified like {@link #NAMES}, just standard RS3 knowledge. */
    public enum Category { COMBAT, GATHERING, ARTISAN, SUPPORT }

    private static final List<Category> CATEGORIES = List.of(
            Category.COMBAT,    // Attack
            Category.COMBAT,    // Defence
            Category.COMBAT,    // Strength
            Category.COMBAT,    // Constitution
            Category.COMBAT,    // Ranged
            Category.COMBAT,    // Prayer
            Category.COMBAT,    // Magic
            Category.ARTISAN,   // Cooking
            Category.GATHERING, // Woodcutting
            Category.ARTISAN,   // Fletching
            Category.GATHERING, // Fishing
            Category.ARTISAN,   // Firemaking
            Category.ARTISAN,   // Crafting
            Category.ARTISAN,   // Smithing
            Category.GATHERING, // Mining
            Category.ARTISAN,   // Herblore
            Category.SUPPORT,   // Agility
            Category.SUPPORT,   // Thieving
            Category.SUPPORT,   // Slayer
            Category.GATHERING, // Farming
            Category.ARTISAN,   // Runecrafting
            Category.SUPPORT,   // Hunter
            Category.ARTISAN,   // Construction
            Category.COMBAT,    // Summoning
            Category.SUPPORT,   // Dungeoneering
            Category.GATHERING, // Divination
            Category.ARTISAN,   // Invention
            Category.GATHERING, // Archaeology
            Category.COMBAT     // Necromancy
    );

    private RuneScapeSkillCatalog() {}

    /** The skill's display name, or {@code "Skill " + skillId} for an ID released after this list was last updated. */
    public static String nameFor(int skillId) {
        return skillId >= 0 && skillId < NAMES.size() ? NAMES.get(skillId) : "Skill " + skillId;
    }

    /** {@code SUPPORT} for an ID released after this list was last updated — an arbitrary but harmless default. */
    public static Category categoryFor(int skillId) {
        return skillId >= 0 && skillId < CATEGORIES.size() ? CATEGORIES.get(skillId) : Category.SUPPORT;
    }

    public static int skillCount() {
        return NAMES.size();
    }
}
