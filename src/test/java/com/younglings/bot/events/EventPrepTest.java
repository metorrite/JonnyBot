package com.younglings.bot.events;

import com.younglings.bot.events.EventPrep.Facts;
import com.younglings.bot.events.EventPrep.Mode;
import com.younglings.bot.events.EventPrep.Prep;
import com.younglings.bot.events.EventPrep.State;
import com.younglings.bot.events.EventPrep.Step;
import com.younglings.bot.events.EventRoster.Gate;
import com.younglings.bot.events.EventRoster.Setting;
import com.younglings.bot.events.EventRoster.Tally;
import com.younglings.bot.events.EventRules.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventPrepTest {
    private static final Tally NOBODY = new Tally(0, 0, 0, 0);

    private static Facts facts(Status status, Mode mode, boolean closed, boolean confirmed, int active, int waiting, Setting verification, Tally tally) {
        return new Facts(status, mode, true, closed, confirmed, active, waiting, verification, tally);
    }

    private static Step step(Prep prep, String key) {
        return prep.steps().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError("no step " + key + " in " + prep.steps()));
    }

    @Test
    void anEventNotFollowingTheSetupStartsWheneverItHasObjectives() {
        Prep draft = EventPrep.of(facts(Status.DRAFT, Mode.OFF, false, false, 0, 0, EventRoster.notRequired(), NOBODY));
        assertTrue(draft.ready(), "no setup, nothing to wait for");
        assertEquals(State.WAITING, step(draft, "signups").state());
        assertFalse(step(draft, "signups").required());
        Prep open = EventPrep.of(facts(Status.SIGNUPS, Mode.OFF, false, false, 12, 0, EventRoster.notRequired(), NOBODY));
        assertTrue(open.ready());
        assertEquals(State.ACTIVE, step(open, "signups").state());
        assertTrue(step(open, "signups").detail().contains("12 players"), step(open, "signups").detail());
        Prep noObjectives = EventPrep.of(new Facts(Status.DRAFT, Mode.OFF, false, false, false, 0, 0, EventRoster.notRequired(), NOBODY));
        assertFalse(noObjectives.ready());
        assertEquals(State.ACTIVE, step(noObjectives, "build").state());
    }

    @Test
    void aStandardEventNeedsSignupsClosedAndTheRosterConfirmedInThatOrder() {
        Prep drafting = EventPrep.of(facts(Status.DRAFT, Mode.STANDARD, false, false, 0, 0, EventRoster.notRequired(), NOBODY));
        assertFalse(drafting.ready());
        assertEquals("Open signups, then close them.", drafting.blockers().get(0));

        Prep open = EventPrep.of(facts(Status.SIGNUPS, Mode.STANDARD, false, false, 20, 3, EventRoster.notRequired(), NOBODY));
        assertFalse(open.ready());
        assertEquals(State.ACTIVE, step(open, "signups").state());
        assertEquals(State.WAITING, step(open, "roster").state(), "the roster can't be confirmed while signups are open");
        assertEquals(java.util.List.of("Close signups."), open.blockers());
        assertTrue(step(open, "signups").detail().contains("3 waiting"), step(open, "signups").detail());

        Prep closed = EventPrep.of(facts(Status.SIGNUPS, Mode.STANDARD, true, false, 20, 3, EventRoster.notRequired(), NOBODY));
        assertEquals(State.DONE, step(closed, "signups").state());
        assertEquals(State.ACTIVE, step(closed, "roster").state());
        assertEquals(java.util.List.of("Confirm the roster."), closed.blockers());

        Prep ready = EventPrep.of(facts(Status.SIGNUPS, Mode.STANDARD, true, true, 20, 3, EventRoster.notRequired(), NOBODY));
        assertTrue(ready.ready());
        assertEquals(State.DONE, step(ready, "roster").state());
    }

    @Test
    void aRunningEventHasItsStepsDoneWhateverWasSkipped() {
        Prep running = EventPrep.of(facts(Status.RUNNING, Mode.OFF, false, false, 8, 0, EventRoster.notRequired(), NOBODY));
        assertEquals(State.DONE, step(running, "signups").state());
        assertEquals(State.DONE, step(running, "roster").state());
        assertTrue(running.ready());
    }

    @Test
    void aCancelledEventSkipsWhatItNeverReached() {
        Prep cancelled = EventPrep.of(facts(Status.CANCELLED, Mode.STANDARD, false, false, 0, 0, EventRoster.notRequired(), NOBODY));
        assertEquals(State.SKIPPED, step(cancelled, "signups").state());
        assertEquals(State.SKIPPED, step(cancelled, "roster").state());
    }

    @Test
    void verificationOnlyAppearsWhenTheEventAsksForIt() {
        assertTrue(EventPrep.of(facts(Status.SIGNUPS, Mode.OFF, false, false, 4, 0, EventRoster.notRequired(), NOBODY)).steps().stream().noneMatch(s -> s.key().equals("verification")));
        Prep asking = EventPrep.of(facts(Status.SIGNUPS, Mode.OFF, false, false, 4, 0, new Setting(true, Gate.TEAMS, ""), new Tally(3, 0, 1, 0)));
        assertEquals(State.ACTIVE, step(asking, "verification").state());
        assertTrue(step(asking, "verification").detail().contains("3 verified"), step(asking, "verification").detail());
        assertTrue(step(asking, "verification").detail().contains("1 waiting for a decision"), step(asking, "verification").detail());
    }

    @Test
    void aGateAtTheStartBlocksUntilEveryoneIsSettledAndOtherGatesOnlyWarn() {
        Setting atStart = new Setting(true, Gate.START, "");
        Prep blocked = EventPrep.of(facts(Status.SIGNUPS, Mode.OFF, false, false, 5, 0, atStart, new Tally(3, 0, 1, 1)));
        assertFalse(blocked.ready(), "two players still need a decision");
        assertTrue(blocked.blockers().get(0).startsWith("2 players still need verification"), blocked.blockers().toString());
        assertTrue(step(blocked, "verification").required());

        Prep settled = EventPrep.of(facts(Status.SIGNUPS, Mode.OFF, false, false, 5, 0, atStart, new Tally(4, 1, 0, 0)));
        assertTrue(settled.ready());
        assertEquals(State.DONE, step(settled, "verification").state());

        Prep atTeams = EventPrep.of(facts(Status.SIGNUPS, Mode.OFF, false, false, 5, 0, new Setting(true, Gate.TEAMS, ""), new Tally(3, 0, 2, 0)));
        assertTrue(atTeams.ready(), "with the gate earlier, players left over are simply not counted");
        assertEquals(1, atTeams.warnings().size());
        assertTrue(atTeams.warnings().get(0).contains("2 players aren't verified"), atTeams.warnings().toString());
        assertFalse(step(atTeams, "verification").required());
    }

    @Test
    void closingReopeningAndConfirmingFollowTheStage() {
        assertTrue(EventPrep.canCloseSignups(Status.SIGNUPS, false));
        assertFalse(EventPrep.canCloseSignups(Status.SIGNUPS, true), "already closed");
        assertFalse(EventPrep.canCloseSignups(Status.DRAFT, false));
        assertFalse(EventPrep.canCloseSignups(Status.RUNNING, false));
        assertTrue(EventPrep.canReopenSignups(Status.SIGNUPS, true));
        assertFalse(EventPrep.canReopenSignups(Status.SIGNUPS, false));
        assertFalse(EventPrep.canReopenSignups(Status.RUNNING, true), "an event that has started doesn't take signups again");
        assertTrue(EventPrep.canConfirmRoster(Status.SIGNUPS, true, false));
        assertFalse(EventPrep.canConfirmRoster(Status.SIGNUPS, false, false), "close signups first");
        assertFalse(EventPrep.canConfirmRoster(Status.SIGNUPS, true, true), "already confirmed");
    }
}
