package com.runeclans.bot.events;

import com.runeclans.bot.events.EventPrep.Facts;
import com.runeclans.bot.events.EventPrep.Mode;
import com.runeclans.bot.events.EventPrep.Prep;
import com.runeclans.bot.events.EventPrep.State;
import com.runeclans.bot.events.EventPrep.Step;
import com.runeclans.bot.events.EventRoster.Gate;
import com.runeclans.bot.events.EventRoster.Setting;
import com.runeclans.bot.events.EventRoster.Tally;
import com.runeclans.bot.events.EventRules.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static Facts withTeams(Status status, Mode mode, boolean confirmed, TeamBuilder.Facts teams, boolean finalised) {
        return new Facts(status, mode, true, confirmed, confirmed, 6, 0, EventRoster.notRequired(), NOBODY, new EventPrep.TeamFacts(teams, finalised));
    }

    @Test
    void aSoloEventHasNoTeamsStep() {
        Prep solo = EventPrep.of(facts(Status.SIGNUPS, Mode.STANDARD, true, true, 6, 0, EventRoster.notRequired(), NOBODY));
        assertTrue(solo.steps().stream().noneMatch(s -> s.key().equals("teams")));
    }

    @Test
    void aTeamEventInAStandardSetupNeedsTheRosterThenEveryoneOnATeamThenTheWordThatTheyAreFinal() {
        Prep waiting = EventPrep.of(withTeams(Status.SIGNUPS, Mode.STANDARD, false, new TeamBuilder.Facts(2, 6, 6, List.of(), 0), false));
        assertEquals(State.WAITING, step(waiting, "teams").state(), "the teams are made from the confirmed roster");
        assertTrue(step(waiting, "teams").required());
        assertTrue(step(waiting, "teams").detail().contains("Confirm the roster first"), step(waiting, "teams").detail());
        assertFalse(waiting.blockers().stream().anyMatch(b -> b.contains("team")), "the roster blocker already says what is next: " + waiting.blockers());

        Prep open = EventPrep.of(withTeams(Status.SIGNUPS, Mode.STANDARD, true, new TeamBuilder.Facts(2, 6, 2, List.of(), 0), false));
        assertEquals(State.ACTIVE, step(open, "teams").state());
        assertFalse(open.ready());
        assertEquals(List.of("2 players still need a team."), open.blockers());
        assertTrue(step(open, "teams").detail().startsWith("2 teams, 4 of 6 players placed."), step(open, "teams").detail());

        Prep empty = EventPrep.of(withTeams(Status.SIGNUPS, Mode.STANDARD, true, new TeamBuilder.Facts(2, 6, 0, List.of("Blue"), 0), false));
        assertEquals(List.of("Team Blue has no players: add some or remove it."), empty.blockers());

        Prep unfinished = EventPrep.of(withTeams(Status.SIGNUPS, Mode.STANDARD, true, new TeamBuilder.Facts(2, 6, 0, List.of(), 0), false));
        assertEquals(List.of("Finalise the teams."), unfinished.blockers());
        assertEquals(State.ACTIVE, step(unfinished, "teams").state());
        assertTrue(step(unfinished, "teams").detail().endsWith("Check them, then finalise."), step(unfinished, "teams").detail());

        Prep done = EventPrep.of(withTeams(Status.SIGNUPS, Mode.STANDARD, true, new TeamBuilder.Facts(2, 6, 0, List.of(), 0), true));
        assertTrue(done.ready());
        assertEquals(State.DONE, step(done, "teams").state());

        Prep changed = EventPrep.of(withTeams(Status.SIGNUPS, Mode.STANDARD, true, new TeamBuilder.Facts(2, 7, 1, List.of(), 0), true));
        assertFalse(changed.ready(), "a late player without a team undoes 'ready' even though the teams were called final");
        assertEquals(List.of("1 player still needs a team."), changed.blockers());
    }

    @Test
    void aTeamEventNotFollowingTheSetupOnlyAdvisesAboutItsTeams() {
        Prep off = EventPrep.of(withTeams(Status.SIGNUPS, Mode.OFF, false, new TeamBuilder.Facts(2, 6, 3, List.of(), 0), false));
        assertTrue(off.ready(), "nothing is required");
        assertFalse(step(off, "teams").required());
        assertEquals(1, off.warnings().size());
        assertTrue(off.warnings().get(0).startsWith("3 players still need a team."), off.warnings().toString());
        Prep fine = EventPrep.of(withTeams(Status.SIGNUPS, Mode.OFF, false, new TeamBuilder.Facts(2, 6, 0, List.of(), 0), false));
        assertEquals(State.DONE, step(fine, "teams").state(), "nothing left to do, and finalising is optional");
        assertTrue(fine.warnings().isEmpty());
    }

    @Test
    void theTeamsStepIsDoneOnceTheEventRunsAndSkippedIfItWasCancelled() {
        Prep running = EventPrep.of(withTeams(Status.RUNNING, Mode.STANDARD, true, new TeamBuilder.Facts(2, 6, 1, List.of(), 0), true));
        assertEquals(State.DONE, step(running, "teams").state());
        assertTrue(step(running, "teams").detail().startsWith("Final: "), step(running, "teams").detail());
        assertEquals(State.SKIPPED, step(EventPrep.of(withTeams(Status.CANCELLED, Mode.STANDARD, false, new TeamBuilder.Facts(0, 0, 0, List.of(), 0), false)), "teams").state());
    }

    private static Facts withSpace(Status status, Mode mode, boolean closed, boolean confirmed, EventPrep.TeamFacts teams, EventPrep.SpaceFacts space) {
        return new Facts(status, mode, true, closed, confirmed, 6, 0, EventRoster.notRequired(), NOBODY, teams, space);
    }

    @Test
    void anEventWithoutRolesAndChannelsHasNoStepForThem() {
        Prep solo = EventPrep.of(facts(Status.SIGNUPS, Mode.STANDARD, true, true, 6, 0, EventRoster.notRequired(), NOBODY));
        assertTrue(solo.steps().stream().noneMatch(s -> s.key().equals("space")));
    }

    @Test
    void rolesAndChannelsWaitForTheStepsBeforeThemThenAreSetUpAndStayRequiredIfSomethingGoesMissing() {
        EventPrep.SpaceFacts none = new EventPrep.SpaceFacts(false, false, "");
        Prep early = EventPrep.of(withSpace(Status.SIGNUPS, Mode.STANDARD, true, false, null, none));
        assertEquals(State.WAITING, step(early, "space").state());
        assertTrue(step(early, "space").detail().contains("Confirm the roster first."), step(early, "space").detail());
        assertFalse(early.blockers().contains("Set up the roles and channels."), "the steps before it already say what is next: " + early.blockers());

        Prep ready = EventPrep.of(withSpace(Status.SIGNUPS, Mode.STANDARD, true, true, null, none));
        assertEquals(State.ACTIVE, step(ready, "space").state());
        assertTrue(step(ready, "space").required());
        assertEquals(List.of("Set up the roles and channels."), ready.blockers());
        assertFalse(ready.ready());

        Prep done = EventPrep.of(withSpace(Status.SIGNUPS, Mode.STANDARD, true, true, null, new EventPrep.SpaceFacts(true, true, "")));
        assertEquals(State.DONE, step(done, "space").state());
        assertTrue(done.ready());

        Prep lost = EventPrep.of(withSpace(Status.SIGNUPS, Mode.STANDARD, true, true, null, new EventPrep.SpaceFacts(true, false, "the channel boss-night-red is missing.")));
        assertEquals(State.ACTIVE, step(lost, "space").state());
        assertEquals(List.of("Bring the roles and channels up to date."), lost.blockers());
        assertTrue(step(lost, "space").detail().contains("boss-night-red"), step(lost, "space").detail());
    }

    @Test
    void aTeamEventsRolesAndChannelsWaitUntilTheTeamsAreFinal() {
        EventPrep.SpaceFacts none = new EventPrep.SpaceFacts(false, false, "");
        TeamBuilder.Facts placed = new TeamBuilder.Facts(2, 6, 0, List.of(), 0);
        Prep unfinished = EventPrep.of(withSpace(Status.SIGNUPS, Mode.STANDARD, true, true, new EventPrep.TeamFacts(placed, false), none));
        assertEquals(State.WAITING, step(unfinished, "space").state());
        assertTrue(step(unfinished, "space").detail().contains("Finalise the teams first."), step(unfinished, "space").detail());
        Prep finished = EventPrep.of(withSpace(Status.SIGNUPS, Mode.STANDARD, true, true, new EventPrep.TeamFacts(placed, true), none));
        assertEquals(State.ACTIVE, step(finished, "space").state());
    }

    @Test
    void anEventNotFollowingTheSetupOnlyAdvisesAboutItsRolesAndChannelsAndTheyCloseOutWhenItStarts() {
        EventPrep.SpaceFacts none = new EventPrep.SpaceFacts(false, false, "");
        Prep off = EventPrep.of(withSpace(Status.SIGNUPS, Mode.OFF, true, true, null, none));
        assertTrue(off.ready());
        assertFalse(step(off, "space").required());
        assertEquals(State.SKIPPED, step(EventPrep.of(withSpace(Status.RUNNING, Mode.STANDARD, true, true, null, none)), "space").state());
        assertEquals(State.DONE, step(EventPrep.of(withSpace(Status.RUNNING, Mode.STANDARD, true, true, null, new EventPrep.SpaceFacts(true, true, ""))), "space").state());
        assertEquals(State.SKIPPED, step(EventPrep.of(withSpace(Status.CANCELLED, Mode.STANDARD, false, false, null, none)), "space").state());
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
