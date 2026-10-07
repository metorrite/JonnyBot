package com.younglings.bot.commands.combat;

import com.younglings.bot.combat.CombatAchievementLoader;
import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatAchievementViewTest {
    private static List<Achievement> achievements;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream stream = CombatAchievementLoader.class.getResourceAsStream(CombatAchievementLoader.RESOURCE)) {
            achievements = CombatAchievementLoader.parse(stream.readAllBytes()).achievements();
        }
    }

    private static Achievement named(String name) {
        return achievements.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static String text(Achievement a) {
        var container = CombatAchievementView.card(a);
        return String.join("\n", ComponentTree.of(List.of(container)).findAll(TextDisplay.class).stream().map(TextDisplay::getContent).toList());
    }

    @Test
    void theCardLinksTheNameToItsWikiPageAndShowsTierScoresAndBoss() {
        Achievement a = named("A Quest for Understanding");
        String text = text(a);
        assertTrue(text.contains("[A Quest for Understanding](https://runescape.wiki/w/A_Quest_for_Understanding)"), text);
        assertTrue(text.contains("**Elite**") && text.contains("4 CombatScore") && text.contains("25 RuneScore") && text.contains("Members"), text);
        assertTrue(text.contains("[Elite Dungeon: Dragonkin Laboratory](") && text.contains("› [Astellarn]("), text);
        assertTrue(text.contains("Defeat Astellarn while at least two pulsars are still active."));
        assertTrue(text.contains("pulsar star"), "the wiki's tips are there");
    }

    @Test
    void theTierIconIsTheWikisOwnPicture() {
        assertEquals("https://runescape.wiki/images/Combat_Mastery_-_Grandmaster_achievement_icon.png", CombatAchievementView.tierIconUrl("Grandmaster"));
    }

    @Test
    void theCardHasALinkButtonToTheWiki() {
        var buttons = ComponentTree.of(List.of(CombatAchievementView.card(named("Aerial Pursuit")))).findAll(Button.class);
        assertEquals(1, buttons.size());
        assertEquals("https://runescape.wiki/w/Aerial_Pursuit", buttons.getFirst().getUrl());
    }

    @Test
    void tipsKeepHeadingsInBoldDropTheSummaryAndCutLongTextAtAParagraph() {
        Achievement acid = named("Acid-washed");
        String tips = CombatAchievementView.tips(acid, 200);
        assertFalse(tips.startsWith(acid.wikiSummary()), "the description above already says it");
        assertTrue(tips.contains("**Strategy**") || tips.contains("**Requirements**"), tips);
        assertFalse(tips.contains("## "));
        assertTrue(tips.length() <= 200, "" + tips.length());
        assertTrue(tips.endsWith("…"));
    }

    @Test
    void everyAchievementsCardFitsInOneMessage() {
        for (Achievement a : achievements) {
            var container = CombatAchievementView.card(a);
            new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();
            int total = ComponentTree.of(List.of(container)).findAll(TextDisplay.class).stream().mapToInt(t -> t.getContent().length()).sum();
            assertTrue(total <= 4000, a.name() + " has " + total + " characters of text");
        }
    }
}
