package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScavengerHuntTest {
    private final ScavengerHuntTemplate template = new ScavengerHuntTemplate();

    @Test
    void theDefaultHuntHasEightProofItemsAllOpenFromTheStart() {
        List<ObjectiveSpec> steps = template.starterObjectives(DataObject.empty());
        assertEquals(8, steps.size());
        assertTrue(steps.stream().allMatch(s -> s.evidence() == Evidence.PROOF && s.prerequisites().isEmpty() && s.points() == 25));
        assertTrue(EventService.checkObjectives(steps).isEmpty());
        assertTrue(template.configProblems(DataObject.empty()).isEmpty());
    }

    @Test
    void anOrderedHuntUnlocksEachItemAfterThePreviousOne() {
        List<ObjectiveSpec> steps = template.starterObjectives(DataObject.fromJson("{\"steps\":4,\"ordered\":true}"));
        assertEquals(List.of(), steps.get(0).prerequisites());
        assertEquals(List.of("item-1"), steps.get(1).prerequisites());
        assertEquals(List.of("item-3"), steps.get(3).prerequisites());
        assertTrue(EventService.checkObjectives(steps).isEmpty(), "a straight chain has no loop");
    }

    @Test
    void theFinishBonusNeedsEveryItemAndIsLeftOutAtZero() {
        DataObject prepared = template.prepare(DataObject.fromJson("{\"steps\":5,\"finishBonus\":150}"));
        var bonuses = EventBonuses.parse(prepared);
        assertEquals(1, bonuses.size());
        assertEquals(150, bonuses.get(0).points());
        assertEquals(5, bonuses.get(0).keys().size());
        Set<String> keys = template.starterObjectives(prepared).stream().map(ObjectiveSpec::key).collect(Collectors.toSet());
        assertTrue(EventBonuses.problems(prepared, keys).isEmpty());
        assertTrue(EventBonuses.parse(template.prepare(DataObject.fromJson("{\"finishBonus\":0}"))).isEmpty());
    }

    @Test
    void badSettingsAreRejected() {
        assertFalse(template.configProblems(DataObject.fromJson("{\"steps\":2}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"steps\":31}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"stepPoints\":-1}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"finishBonus\":1000000}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"steps\":\"many\"}")).isEmpty());
    }

    @Test
    void finishingEveryItemEarnsTheBonus() {
        DataObject prepared = template.prepare(DataObject.fromJson("{\"steps\":3,\"stepPoints\":10,\"finishBonus\":50}"));
        var objectives = template.starterObjectives(prepared);
        var bonuses = EventBonuses.parse(prepared);
        var t0 = java.time.Instant.parse("2026-10-20T12:00:00Z");
        var log = new java.util.ArrayList<EventScoring.Entry>();
        for (int i = 0; i < 3; i++) log.add(new EventScoring.Entry("u:1", objectives.get(i).key(), 1, t0.plusSeconds(i)));
        assertEquals(30 + 50, EventScoring.standings(objectives, log, bonuses).get(0).points());
        assertEquals(20, EventScoring.standings(objectives, log.subList(0, 2), bonuses).get(0).points());
    }
}
