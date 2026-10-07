package com.younglings.bot.combat;

import java.util.List;
import java.util.Map;

/**
 * The Combat Mastery achievements from the RuneScape Wiki, as the bot keeps them: every achievement with the wiki's own description and tips,
 * and the six tiers. The data is built by {@code tools/ca_sync.py}, committed as {@code catalog/combat_achievements.json}, and loaded into the
 * database on startup. Icon fields are paths on the clan website ({@code /combat/tier-easy.png}), where the downloaded pictures live.
 */
public final class CombatAchievementModels {
    private CombatAchievementModels() {}

    /** A Combat Mastery tier, Easy (1) to Grandmaster (6). */
    public record Tier(int number, String name, String icon, String wikiUrl, String reward, int combatScorePer, int achievementCount) {}

    /** An achievement another one lists (what a tier, perfect or unorthodox achievement asks for). */
    public record Requirement(int position, Long achievementId, String name) {}

    /**
     * One achievement. {@code name} links to {@code wikiUrl}, its own wiki page. {@code subcategory} is the boss or activity and
     * {@code subsubcategory} the part of it (a boss within a dungeon), or null. {@code wikiSummary} is the page's first paragraph and
     * {@code wikiText} everything useful on it (strategy, tips, requirements) as plain text with {@code ##} headings.
     */
    public record Achievement(long id, String name, String wikiTitle, String wikiUrl, String description,
                              boolean members, String membersIcon,
                              String subcategory, String subcategoryUrl, String subsubcategory, String subsubcategoryUrl,
                              int tierNumber, String tier, String tierIcon,
                              int combatScore, String combatScoreIcon, int runeScore, String runeScoreIcon,
                              String wikiSummary, String wikiText, Map<String, String> infobox, List<Requirement> requirements) {}
}
