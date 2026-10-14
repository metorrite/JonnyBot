package com.younglings.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossNightTest {
    private final BossNightTemplate template = new BossNightTemplate();

    @Test
    void theDefaultIsTenKillsAtTenPointsEach() {
        assertTrue(template.configProblems(DataObject.empty()).isEmpty());
        var o = template.starterObjectives(DataObject.empty()).get(0);
        assertEquals("boss-kill", o.key());
        assertEquals("Kill the boss", o.label());
        assertEquals(10, o.repeatLimit());
        assertEquals(10, o.points());
        assertTrue(EventService.checkObjectives(List.of(o)).isEmpty());
    }

    @Test
    void theBossNameAndCountsAreUsed() {
        var o = template.starterObjectives(DataObject.fromJson("{\"boss\":\" Zamorak \",\"kills\":25,\"pointsPerKill\":4}")).get(0);
        assertEquals("Kill Zamorak", o.label());
        assertEquals(25, o.repeatLimit());
        assertEquals(4, o.points());
    }

    @Test
    void badSettingsAreRejected() {
        assertFalse(template.configProblems(DataObject.fromJson("{\"kills\":0}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"kills\":5000}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"pointsPerKill\":-1}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"boss\":\"" + "x".repeat(41) + "\"}")).isEmpty());
    }

    @Test
    void killsCountUpToTheLimit() {
        var objectives = template.starterObjectives(DataObject.fromJson("{\"kills\":3,\"pointsPerKill\":10}"));
        var log = new java.util.ArrayList<EventScoring.Entry>();
        for (int i = 0; i < 5; i++) log.add(new EventScoring.Entry("t:1", "boss-kill", 1, Instant.parse("2026-10-20T20:00:00Z").plusSeconds(i)));
        assertEquals(30, EventScoring.standings(objectives, log).get(0).points(), "kills past the limit don't add points");
    }
}
