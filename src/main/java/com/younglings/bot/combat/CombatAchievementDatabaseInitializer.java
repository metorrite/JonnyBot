package com.younglings.bot.combat;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The Combat Mastery achievement tables: the six tiers, every achievement (shaped like the wiki's own table, plus the text of each
 * achievement's wiki page), and the achievements each one lists. Filled from the committed catalogue by {@link CombatAchievementLoader}.
 */
@BService
public class CombatAchievementDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(CombatAchievementDatabaseInitializer.class);

    public CombatAchievementDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "combat achievement", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                // Easy (1) to Grandmaster (6): the wiki page, the completion reward, and the CombatScore each achievement in the tier is worth.
                """
                CREATE TABLE IF NOT EXISTS younglings.combat_achievement_tier (
                    number INTEGER PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    icon TEXT NULL,
                    wiki_url TEXT NULL,
                    reward TEXT NULL,
                    combat_score_per INTEGER NOT NULL,
                    achievement_count INTEGER NOT NULL
                );
                """,

                // One row per achievement, keyed by the wiki's own id. name + wiki_url is the "name with a link"; the *_icon columns are
                // paths on the clan website for the tier, CombatScore, RuneScore and members/free-to-play pictures.
                """
                CREATE TABLE IF NOT EXISTS younglings.combat_achievement (
                    id BIGINT PRIMARY KEY,
                    name TEXT NOT NULL,
                    wiki_title TEXT NOT NULL,
                    wiki_url TEXT NOT NULL,
                    description TEXT NOT NULL,
                    members BOOLEAN NOT NULL,
                    members_icon TEXT NULL,
                    subcategory TEXT NULL,
                    subcategory_url TEXT NULL,
                    subsubcategory TEXT NULL,
                    subsubcategory_url TEXT NULL,
                    tier_number INTEGER NOT NULL REFERENCES younglings.combat_achievement_tier(number),
                    tier TEXT NOT NULL,
                    tier_icon TEXT NULL,
                    combat_score INTEGER NOT NULL,
                    combat_score_icon TEXT NULL,
                    rune_score INTEGER NOT NULL,
                    rune_score_icon TEXT NULL,
                    wiki_summary TEXT NULL,
                    wiki_text TEXT NULL,
                    infobox TEXT NOT NULL DEFAULT '{}',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS combat_achievement_name_idx ON younglings.combat_achievement (LOWER(name));",
                "CREATE INDEX IF NOT EXISTS combat_achievement_tier_idx ON younglings.combat_achievement (tier_number);",
                "CREATE INDEX IF NOT EXISTS combat_achievement_subcategory_idx ON younglings.combat_achievement (LOWER(subcategory));",

                // The achievements an achievement lists, in order (a perfect or unorthodox achievement's targets).
                """
                CREATE TABLE IF NOT EXISTS younglings.combat_achievement_requirement (
                    achievement_id BIGINT NOT NULL REFERENCES younglings.combat_achievement(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    required_id BIGINT NULL,
                    required_name TEXT NOT NULL,
                    PRIMARY KEY (achievement_id, position)
                );
                """,

                // What was last loaded, so a restart only reloads when the committed catalogue changed.
                """
                CREATE TABLE IF NOT EXISTS younglings.combat_achievement_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                """
        ));
    }
}
