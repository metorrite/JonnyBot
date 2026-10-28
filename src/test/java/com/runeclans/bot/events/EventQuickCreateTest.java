package com.runeclans.bot.events;

import com.runeclans.bot.events.EventQuickCreate.Kind;
import com.runeclans.bot.events.EventService.EventInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventQuickCreateTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private static EventInput plan(Kind kind, String detail) {
        return EventQuickCreate.plan(kind, "  Friday night  ", 7, 24, detail, NOW).input();
    }

    /** The plan has to be something the engine accepts, template settings included. */
    private static void assertValid(EventInput in, EventTemplate template) {
        assertEquals(List.of(), EventRules.eventProblems(in.name(), in.description(), in.maxParticipants(), in.startsAt(), in.endsAt()));
        assertEquals(List.of(), template.configProblems(in.config()));
        assertEquals(List.of(), EventSettings.problems(in.config()));
    }

    @Test
    void theEventStartsWhenAskedAndRunsForTheDaysGiven() {
        EventInput in = plan(Kind.BINGO, null);
        assertEquals(NOW.plus(Duration.ofHours(24)), in.startsAt());
        assertEquals(in.startsAt().plus(Duration.ofDays(7)), in.endsAt());
        assertEquals("  Friday night  ".strip(), in.name(), "names are trimmed");
        assertEquals(EventRules.Scope.PRIVATE, in.scope());
    }

    @Test
    void bingoUsesTheUsualBoardOrTheSizeGiven() {
        assertEquals(BingoTemplate.DEFAULT_SIZE, plan(Kind.BINGO, null).config().getObject("board").getInt("size"));
        assertEquals(4, plan(Kind.BINGO, " 4 ").config().getObject("board").getInt("size"));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.BINGO, "9"));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.BINGO, "big"));
    }

    @Test
    void bingoCanBeStartedInOneOfTheBoardShapes() {
        assertEquals("pyramid", plan(Kind.BINGO, " Pyramid ").config().getObject("board").getString("layout"));
        assertEquals("tree", plan(Kind.BINGO, "world tree").config().getObject("board").getString("layout"));
        assertEquals("convergence", plan(Kind.BINGO, "convergence").config().getObject("board").getString("layout"));
        assertEquals("path", plan(Kind.BINGO, "path").config().getObject("board").getString("layout"));
        assertFalse(plan(Kind.BINGO, "pyramid").config().getObject("board").hasKey("size"), "a shape has no square size");
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.BINGO, "spiral"));
    }

    @Test
    void aBingoIsDrawnInTheDefaultLookUnlessOneIsChosen() {
        assertEquals(BingoLooks.DEFAULT_FOR_NEW, plan(Kind.BINGO, null).config().getObject("board").getString("look"));
        assertEquals(BingoLooks.DEFAULT_FOR_NEW, plan(Kind.BINGO, "pyramid").config().getObject("board").getString("look"), "shapes get it too");
        EventInput chosen = EventQuickCreate.plan(Kind.BINGO, "Look night", 7, 24, "pyramid", NOW, BingoLooks.CLASSIC).input();
        assertEquals(BingoLooks.CLASSIC, chosen.config().getObject("board").getString("look"));
        assertValid(chosen, new BingoTemplate());
        assertThrows(IllegalArgumentException.class, () -> EventQuickCreate.plan(Kind.BINGO, "x", 7, 24, null, NOW, "neon"));
        assertFalse(plan(Kind.COMPETITION, null).config().hasKey("board"), "only a bingo has a board to draw");
        assertTrue(EventQuickCreate.plan(Kind.BINGO, "x", 7, 24, "3", NOW, BingoLooks.RUNESCAPE).summary().contains("RuneScape"));
        assertEquals("RuneScape", EventQuickCreate.Look.RUNESCAPE.toString());
        assertEquals(BingoLooks.CLASSIC, EventQuickCreate.Look.TEMPLATE.id);
    }

    @Test
    void aQuickEventIsTheStandardOneWithTheSetupStepsOff() {
        for (Kind kind : new Kind[]{Kind.SCAVENGER_HUNT, Kind.COMPETITION}) {
            EventInput quick = plan(kind, kind == Kind.COMPETITION ? "overall" : null);
            EventTemplate template = kind == Kind.COMPETITION ? new CompetitionTemplate() : new ScavengerHuntTemplate();
            EventInput standard = EventOptions.toInput(EventOptions.normalize(EventOptions.standard(template, NOW)));
            assertEquals(standard.scope(), quick.scope(), kind.name());
            assertEquals(standard.scoringMode(), quick.scoringMode());
            assertEquals(standard.maxParticipants(), quick.maxParticipants());
            assertEquals(standard.startsAt(), quick.startsAt());
            assertEquals(standard.endsAt(), quick.endsAt());
            assertEquals("STANDARD", EventSettings.setupModeOf(standard.config()).name(), "the dashboard's standard setup follows the steps");
            assertEquals("OFF", EventSettings.setupModeOf(quick.config()).name(), "an event made from one Discord command doesn't");
            for (String key : standard.config().keys()) {
                if (key.equals("setup")) continue;
                assertEquals(standard.config().get(key), quick.config().get(key), kind + ": " + key);
            }
        }
    }

    @Test
    void aCompetitionIsOverallUnlessASkillIsNamed() {
        assertEquals(CompetitionTemplate.OVERALL, plan(Kind.COMPETITION, null).config().getInt("skillId"));
        assertEquals(CompetitionTemplate.OVERALL, plan(Kind.COMPETITION, "Overall").config().getInt("skillId"));
        assertEquals(18, plan(Kind.COMPETITION, " slayer ").config().getInt("skillId"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> plan(Kind.COMPETITION, "Sailing"));
        assertTrue(unknown.getMessage().contains("Sailing"), unknown.getMessage());
    }

    @Test
    void aBossNightNeedsABoss() {
        assertEquals("Araxxor", plan(Kind.BOSS_NIGHT, " Araxxor ").config().getString("boss"));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.BOSS_NIGHT, null));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.BOSS_NIGHT, "  "));
    }

    @Test
    void aScavengerHuntHasThreeToThirtyItems() {
        assertEquals(8, plan(Kind.SCAVENGER_HUNT, null).config().getInt("steps"));
        assertEquals(12, plan(Kind.SCAVENGER_HUNT, "12").config().getInt("steps"));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.SCAVENGER_HUNT, "2"));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.SCAVENGER_HUNT, "31"));
    }

    @Test
    void timesAreKeptInRange() {
        assertThrows(IllegalArgumentException.class, () -> EventQuickCreate.plan(Kind.BINGO, "x", 0, 24, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> EventQuickCreate.plan(Kind.BINGO, "x", EventQuickCreate.MAX_DAYS + 1, 24, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> EventQuickCreate.plan(Kind.BINGO, "x", 7, -1, null, NOW));
        assertEquals(NOW, EventQuickCreate.plan(Kind.BINGO, "x", 1, 0, null, NOW).input().startsAt(), "may start straight away");
    }

    @Test
    void everyKindProducesSettingsTheTemplateAccepts() {
        assertValid(plan(Kind.BINGO, "3"), new BingoTemplate());
        assertValid(plan(Kind.COMPETITION, "Mining"), new CompetitionTemplate());
        assertValid(plan(Kind.BOSS_NIGHT, "Telos"), new BossNightTemplate());
        assertValid(plan(Kind.SCAVENGER_HUNT, "5"), new ScavengerHuntTemplate());
        for (Kind kind : Kind.values()) {
            assertTrue(!kind.label.isEmpty() && kind.detailHelp.length() <= 100, kind + " help has to fit a Discord option description");
        }
    }

    @Test
    void theSummaryNamesWhatWasMade() {
        String summary = EventQuickCreate.plan(Kind.BOSS_NIGHT, "x", 2, 0, "Telos", NOW).summary();
        assertTrue(summary.contains("Boss night") && summary.contains("Telos") && summary.contains("2 days"), summary);
    }
}
