package com.younglings.bot.combat;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementModels.Tier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the committed catalogue file ({@code tools/ca_sync.py} writes it) so a bad refresh of the wiki data is caught before it reaches a database. */
class CombatAchievementCatalogTest {
    private static final java.util.regex.Pattern HTML_TAG =
            java.util.regex.Pattern.compile("(?i)</?(span|div|a|table|tr|td|th|li|ul|ol|p|sup|sub|br|img|b|i|small|style|script)[\\s/>]");

    private static CombatAchievementLoader.Catalog catalog;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream stream = CombatAchievementLoader.class.getResourceAsStream(CombatAchievementLoader.RESOURCE)) {
            assertNotNull(stream, "catalog/combat_achievements.json is missing; run tools/ca_sync.py");
            catalog = CombatAchievementLoader.parse(stream.readAllBytes());
        }
    }

    @Test
    void thereAreSixTiersFromEasyToGrandmasterWithTheirIcons() {
        List<Tier> tiers = catalog.tiers();
        assertEquals(List.of("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"), tiers.stream().map(Tier::name).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), tiers.stream().map(Tier::number).toList());
        for (Tier tier : tiers) {
            assertEquals(tier.number(), tier.combatScorePer(), "CombatScore per achievement equals the tier number");
            assertTrue(tier.icon().startsWith("/combat/tier-"), tier.icon());
            assertTrue(tier.wikiUrl().startsWith("https://runescape.wiki/w/Combat_Mastery_-_"), tier.wikiUrl());
        }
    }

    @Test
    void everyTierHoldsAsManyAchievementsAsItsRowSays() {
        Map<Integer, Long> counts = catalog.achievements().stream().collect(Collectors.groupingBy(Achievement::tierNumber, Collectors.counting()));
        for (Tier tier : catalog.tiers()) {
            assertEquals(tier.achievementCount(), counts.getOrDefault(tier.number(), 0L).intValue(), tier.name());
        }
        assertEquals(590, catalog.achievements().size(), "the wiki lists 590 achievements (22 + 60 + 173 + 185 + 102 + 48)");
    }

    @Test
    void everyAchievementHasANameWithItsOwnWikiLinkAndTheColumnsTheWikiTableHas() {
        Set<Long> ids = new HashSet<>();
        for (Achievement a : catalog.achievements()) {
            assertTrue(ids.add(a.id()), "duplicate id " + a.id());
            assertFalse(a.name().isBlank());
            assertTrue(a.wikiUrl().startsWith("https://runescape.wiki/w/"), a.name() + ": " + a.wikiUrl());
            assertFalse(a.description().isBlank(), a.name());
            assertNotNull(a.subcategory(), a.name() + " has no subcategory");
            assertTrue(a.tierIcon().startsWith("/combat/tier-"), a.name());
            assertEquals("/combat/combat-score.png", a.combatScoreIcon());
            assertEquals("/combat/rune-score.png", a.runeScoreIcon());
            assertEquals(a.members() ? "/combat/members.png" : "/combat/free.png", a.membersIcon());
            assertTrue(a.runeScore() > 0, a.name() + " should be worth RuneScore");
            assertEquals(catalog.tiers().get(a.tierNumber() - 1).combatScorePer(), a.combatScore(), a.name() + ": CombatScore follows its tier");
            if (a.subsubcategory() != null) assertNotNull(a.subsubcategoryUrl(), a.name());
        }
    }

    @Test
    void everyAchievementCarriesWhatItsOwnWikiPageSays() {
        for (Achievement a : catalog.achievements()) {
            assertNotNull(a.wikiSummary(), a.name() + " has no summary from its wiki page");
            assertNotNull(a.wikiText(), a.name() + " has no text from its wiki page");
            assertTrue(a.wikiText().startsWith(a.wikiSummary()), a.name() + ": the text begins with the summary");
            // Game messages quoted on the wiki can contain things like "<Player>"; only real HTML tags count as leftover markup.
            assertFalse(HTML_TAG.matcher(a.wikiText()).find(), a.name() + " has leftover HTML: " + a.wikiText());
            assertFalse(a.infobox().isEmpty(), a.name() + " has no infobox");
            assertEquals("Combat", a.infobox().get("Category"), a.name());
        }
    }

    @Test
    void aKnownAchievementComesThroughWithEverything() {
        Achievement a = catalog.achievements().stream().filter(x -> x.name().equals("A Quest for Understanding")).findFirst().orElseThrow();
        assertEquals(1520L, a.id());
        assertEquals("https://runescape.wiki/w/A_Quest_for_Understanding", a.wikiUrl());
        assertEquals("Elite Dungeon: Dragonkin Laboratory", a.subcategory());
        assertEquals("Astellarn", a.subsubcategory());
        assertEquals("Elite", a.tier());
        assertEquals(4, a.combatScore());
        assertEquals(25, a.runeScore());
        assertTrue(a.members());
        assertTrue(a.wikiText().contains("pulsar"), "the wiki's strategy text is kept");
    }

    @Test
    void perfectAndUnorthodoxAchievementsRememberTheAchievementsTheyList() {
        Achievement solak = catalog.achievements().stream().filter(x -> x.name().equals("Solak, Guardian of the Grove (perfect)")).findFirst().orElseThrow();
        assertFalse(solak.requirements().isEmpty());
        assertTrue(solak.requirements().stream().anyMatch(r -> r.name().equals("Target Practice")));
        assertEquals(0, solak.requirements().getFirst().position());
    }

    @Test
    void theFilesFingerprintChangesWithItsContent() {
        assertEquals(CombatAchievementLoader.hashOf("a".getBytes()), CombatAchievementLoader.hashOf("a".getBytes()));
        assertFalse(CombatAchievementLoader.hashOf("a".getBytes()).equals(CombatAchievementLoader.hashOf("b".getBytes())));
    }
}
