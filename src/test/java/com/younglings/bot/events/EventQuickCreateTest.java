package com.younglings.bot.events;

import com.younglings.bot.events.EventQuickCreate.Kind;
import com.younglings.bot.events.EventService.EventInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
