package com.runeclans.bot.events;

import com.runeclans.bot.events.SkillWeekRules.Winner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Skill of the week (T-541): the week's span, the rota, and the winners from the XP gained. */
class SkillWeekRulesTest {
    @Test
    void theWeekRunsFromMondayMidnightToSundayAtOneSecondToMidnightUtc() {
        Instant monday = Instant.parse("2026-10-26T00:00:00Z");
        assertEquals(monday, SkillWeekRules.weekStart(Instant.parse("2026-10-27T13:45:00Z")));
        assertEquals(monday, SkillWeekRules.weekStart(monday));
        assertEquals(monday, SkillWeekRules.weekStart(Instant.parse("2026-11-01T23:59:59Z")));
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), SkillWeekRules.weekStart(Instant.parse("2026-11-02T00:00:00Z")));
        assertEquals(Instant.parse("2026-11-01T23:59:59Z"), SkillWeekRules.weekEnd(monday));
        assertFalse(SkillWeekRules.closeDue(SkillWeekRules.weekEnd(monday), Instant.parse("2026-11-02T00:05:00Z")), "the ledger is a minute or two behind");
        assertTrue(SkillWeekRules.closeDue(SkillWeekRules.weekEnd(monday), Instant.parse("2026-11-02T00:10:00Z")));
    }

    @Test
    void theRotaGoesRoundAndOnlyTakesSkills() {
        List<Integer> rota = List.of(9, 15, 25);
        assertEquals(9, SkillWeekRules.skillAt(rota, 0));
        assertEquals(25, SkillWeekRules.skillAt(rota, 2));
        assertEquals(9, SkillWeekRules.skillAt(rota, 3));
        assertEquals(-1, SkillWeekRules.skillAt(List.of(), 4));
        assertTrue(SkillWeekRules.rotaProblems(rota, true).isEmpty());
        assertTrue(SkillWeekRules.rotaProblems(List.of(), false).isEmpty(), "off with nothing chosen is fine");
        assertEquals(1, SkillWeekRules.rotaProblems(List.of(), true).size());
        assertEquals(1, SkillWeekRules.rotaProblems(List.of(-1), true).size(), "overall isn't a skill of the week");
        assertTrue(SkillWeekRules.rotaProblems(java.util.Collections.nCopies(SkillWeekRules.MAX_ROTA + 1, 1), true).contains("A rota holds at most " + SkillWeekRules.MAX_ROTA + " skills."));
        assertEquals(List.of("Each skill can be in the rota once."), SkillWeekRules.rotaProblems(List.of(9, 15, 9), true), "a skill twice would leave the rota unsure where it is");
    }

    @Test
    void aChangedRotaGoesOnAfterThisWeeksSkill() {
        assertEquals(1, SkillWeekRules.positionAfter(List.of(9, 14, 28), 9), "Necromancy added in a Fletching week: Mining is next, not Fletching again");
        assertEquals(15, SkillWeekRules.skillAt(List.of(25, 9, 15), SkillWeekRules.positionAfter(List.of(25, 9, 15), 9)));
        assertEquals(0, SkillWeekRules.positionAfter(List.of(14, 15), 9), "this week's skill left the rota");
        assertEquals(0, SkillWeekRules.positionAfter(List.of(9, 15), null), "no week yet");
    }

    @Test
    void aWeekThatCantBeBroughtUpToDateIsClosedAnywayADayAfter() {
        Instant end = SkillWeekRules.weekEnd(Instant.parse("2026-10-26T00:00:00Z"));
        assertFalse(SkillWeekRules.giveUp(end, Instant.parse("2026-11-02T00:20:00Z")));
        assertTrue(SkillWeekRules.giveUp(end, Instant.parse("2026-11-02T23:59:59Z")));
    }

    @Test
    void theObjectiveHoldsAWeekAboveTheSkillCap() {
        net.dv8tion.jda.api.utils.data.DataObject config = net.dv8tion.jda.api.utils.data.DataObject.empty().put("skillId", 9).put("xpPerPoint", SkillWeekRules.XP_PER_POINT)
                .put("xpSource", CompetitionTemplate.SOURCE_PLUGIN).put("wholeSpan", true);
        CompetitionTemplate template = new CompetitionTemplate();
        assertTrue(template.configProblems(config).isEmpty());
        EventRules.ObjectiveSpec objective = template.starterObjectives(config).get(0);
        long gain = 150_000_000L; // a Double XP week in a fast skill, over the old 100M cap
        assertTrue(objective.target() * objective.repeatLimit() >= 200_000_000L, "room for a whole skill's 200M");
        assertEquals(gain, Math.min(gain, objective.target() * objective.repeatLimit()));
    }

    @Test
    void winnersAreTheMostXpWithTiesSharingARankAndNobodyOnNothing() {
        Map<Long, Long> xp = new LinkedHashMap<>();
        xp.put(5L, 1_000L);
        xp.put(3L, 9_000L);
        xp.put(4L, 9_000L);
        xp.put(1L, 0L);
        xp.put(2L, 500L);
        assertEquals(List.of(new Winner(1, 3, 9_000), new Winner(1, 4, 9_000), new Winner(3, 5, 1_000)), SkillWeekRules.winners(xp, 3));
        assertEquals(List.of(new Winner(1, 3, 9_000), new Winner(1, 4, 9_000)), SkillWeekRules.winners(xp, 2));
        assertTrue(SkillWeekRules.winners(Map.of(1L, 0L), 3).isEmpty());
    }

    @Test
    void theEventIsNamedAfterTheSkillAndTheMonday() {
        assertEquals("Skill of the week: " + CompetitionTemplate.skillName(9) + " (26 Oct)", SkillWeekRules.eventName(9, Instant.parse("2026-10-26T00:00:00Z")));
    }
}
