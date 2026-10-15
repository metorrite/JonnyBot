package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.events.EventRules.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRulesTest {
    private static ObjectiveSpec obj(String key, String... prerequisites) {
        return new ObjectiveSpec(key, "Objective " + key, "", 10, Evidence.EITHER, 1, 1, List.of(prerequisites));
    }

    @Test
    void anEventMovesForwardAndCanBeCancelledUntilItIsDone() {
        assertTrue(EventRules.canMove(Status.DRAFT, Status.SIGNUPS));
        assertTrue(EventRules.canMove(Status.SIGNUPS, Status.RUNNING));
        assertTrue(EventRules.canMove(Status.RUNNING, Status.SCORING));
        assertTrue(EventRules.canMove(Status.SCORING, Status.DONE));
        assertTrue(EventRules.canMove(Status.SCORING, Status.RUNNING), "results can be reopened");
        assertFalse(EventRules.canMove(Status.DRAFT, Status.DONE));
        assertFalse(EventRules.canMove(Status.RUNNING, Status.DRAFT));
        assertFalse(EventRules.canMove(Status.DONE, Status.RUNNING));
        assertFalse(EventRules.canMove(Status.CANCELLED, Status.DRAFT));
        for (Status s : Status.values()) {
            if (s != Status.DONE && s != Status.CANCELLED) assertTrue(EventRules.canMove(s, Status.CANCELLED), s + " can be cancelled");
        }
        assertTrue(EventRules.next(Status.DONE).isEmpty());
    }

    @Test
    void stagesDecideWhatIsAllowed() {
        assertTrue(EventRules.editable(Status.DRAFT));
        assertTrue(EventRules.editable(Status.SIGNUPS));
        assertFalse(EventRules.editable(Status.RUNNING));
        assertTrue(EventRules.acceptsJoins(Status.SIGNUPS));
        assertTrue(EventRules.acceptsJoins(Status.RUNNING));
        assertFalse(EventRules.acceptsJoins(Status.DONE));
        assertTrue(EventRules.acceptsProgress(Status.RUNNING));
        assertFalse(EventRules.acceptsProgress(Status.SIGNUPS));
        assertFalse(EventRules.acceptsProgress(Status.SCORING));
    }

    @Test
    void anEventNeedsAGoodNameAndSensibleLimits() {
        Instant t = Instant.parse("2026-10-20T12:00:00Z");
        assertTrue(EventRules.eventProblems("Friday Bingo", "Fun", 50, t, t.plusSeconds(3600)).isEmpty());
        assertFalse(EventRules.eventProblems("ab", "", 0, null, null).isEmpty());
        assertFalse(EventRules.eventProblems("x".repeat(EventRules.MAX_NAME + 1), "", 0, null, null).isEmpty());
        assertFalse(EventRules.eventProblems("Friday Bingo", "", -1, null, null).isEmpty());
        assertFalse(EventRules.eventProblems("Friday Bingo", "", EventRules.MAX_PARTICIPANTS + 1, null, null).isEmpty());
        assertFalse(EventRules.eventProblems("Friday Bingo", "", 0, t, t).isEmpty(), "must end after it starts");
        assertNotNull(EventRules.teamNameProblem(""));
        assertNull(EventRules.teamNameProblem("Red Team"));
    }

    @Test
    void keysAreMadeFromLabels() {
        assertEquals("kill-zuk", EventRules.keyFrom("Kill Zuk!"));
        assertEquals("a-b-c", EventRules.keyFrom("  a -- b  c "));
        assertTrue(EventRules.validKey("kill-zuk"));
        assertFalse(EventRules.validKey("Kill Zuk"));
        assertFalse(EventRules.validKey("-bad"));
        assertFalse(EventRules.validKey(""));
        assertTrue(EventRules.keyFrom("x".repeat(100)).length() <= 40);
    }

    @Test
    void anObjectiveIsChecked() {
        assertTrue(EventRules.objectiveProblems(obj("kill-zuk")).isEmpty());
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("kill-zuk", "", "", 10, Evidence.PROOF, 1, 1, List.of())).isEmpty(), "needs a title");
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("Bad Key", "T", "", 10, Evidence.PROOF, 1, 1, List.of())).isEmpty());
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("k", "T", "", -1, Evidence.PROOF, 1, 1, List.of())).isEmpty());
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("k", "T", "", 10, Evidence.PROOF, 0, 1, List.of())).isEmpty());
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("k", "T", "", 10, Evidence.PROOF, 1, 0, List.of())).isEmpty());
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("k", "T", "", 10, null, 1, 1, List.of())).isEmpty());
        assertFalse(EventRules.objectiveProblems(new ObjectiveSpec("k", "T", "", 10, Evidence.PROOF, 1, 1, List.of("k"))).isEmpty(), "can't require itself");
    }

    @Test
    void prerequisitesMustExistAndMustNotLoop() {
        assertTrue(EventRules.objectiveSetProblems(List.of(obj("a"), obj("b", "a"), obj("c", "a", "b"))).isEmpty());
        assertFalse(EventRules.objectiveSetProblems(List.of(obj("a"), obj("a"))).isEmpty(), "duplicate keys");
        assertFalse(EventRules.objectiveSetProblems(List.of(obj("a", "ghost"))).isEmpty(), "unknown prerequisite");
        assertFalse(EventRules.objectiveSetProblems(List.of(obj("a", "b"), obj("b", "a"))).isEmpty(), "two-step loop");
        assertFalse(EventRules.objectiveSetProblems(List.of(obj("a", "c"), obj("b", "a"), obj("c", "b"))).isEmpty(), "three-step loop");
    }

    @Test
    void evidenceModesDecideWhereProgressMayComeFrom() {
        assertTrue(EventRules.evidenceAllows(Evidence.PROOF, Source.CLAIM));
        assertFalse(EventRules.evidenceAllows(Evidence.PROOF, Source.AUTO));
        assertTrue(EventRules.evidenceAllows(Evidence.AUTO, Source.AUTO));
        assertFalse(EventRules.evidenceAllows(Evidence.AUTO, Source.CLAIM));
        assertTrue(EventRules.evidenceAllows(Evidence.EITHER, Source.CLAIM));
        assertTrue(EventRules.evidenceAllows(Evidence.EITHER, Source.AUTO));
        assertFalse(EventRules.evidenceAllows(Evidence.ORGANISER, Source.CLAIM));
        for (Evidence e : Evidence.values()) assertTrue(EventRules.evidenceAllows(e, Source.MANUAL), "an organiser can always record by hand");
    }
}
