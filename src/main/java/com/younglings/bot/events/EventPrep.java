package com.younglings.bot.events;

import com.younglings.bot.events.EventRoster.Gate;
import com.younglings.bot.events.EventRules.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * How ready an event is to start, as a checklist an organiser can follow: build it, take signups, close them and confirm the roster, sort out
 * verification. The event's stage ({@link Status}) stays as it was; this reads the facts about the event and says what is done, what to do now and
 * what stops it from starting. A step that doesn't apply to this event is left out, and later steps (teams, channels) are added here as they exist.
 * <p>
 * How strict it is depends on the event's {@link Mode}: an event on {@code OFF} (what every event was before this) starts whenever an organiser says,
 * so the signup and roster steps only advise; the other modes make closing signups and confirming the roster required. Verification is checked
 * only when the event asks for it, and only blocks the start when it has to be settled by then ({@link Gate#START}). Pure, so it can be tested.
 */
public final class EventPrep {
    /** How much of the setup an event follows: none of it (organisers start it when they like), the standard steps, or all of them. */
    public enum Mode { OFF, STANDARD, FULL }

    /** Where a step is: not reachable yet, the one to do now, done, or not part of this event. */
    public enum State { WAITING, ACTIVE, DONE, SKIPPED }

    /** One line of the checklist. {@code required} says whether it holds the start back; {@code blockers} is why it is, in words. */
    public record Step(String key, String label, State state, boolean required, String detail, List<String> blockers) {
        public Step {
            blockers = List.copyOf(blockers);
        }
    }

    /** What the checklist is worked out from. The counts are of players who hold a place (not the waitlist), except {@code waiting}. */
    public record Facts(Status status, Mode mode, boolean hasObjectives, boolean signupsClosed, boolean rosterConfirmed, int active, int waiting,
                        EventRoster.Setting verification, EventRoster.Tally tally) {}

    /** The checklist, everything that stops the start, and things worth knowing that don't. */
    public record Prep(List<Step> steps, List<String> blockers, List<String> warnings) {
        public Prep {
            steps = List.copyOf(steps);
            blockers = List.copyOf(blockers);
            warnings = List.copyOf(warnings);
        }

        public boolean ready() {
            return blockers.isEmpty();
        }
    }

    private EventPrep() {}

    public static boolean canCloseSignups(Status status, boolean closed) {
        return status == Status.SIGNUPS && !closed;
    }

    /** Signups can be reopened until the event moves on (later steps, like finishing teams, tighten this). */
    public static boolean canReopenSignups(Status status, boolean closed) {
        return status == Status.SIGNUPS && closed;
    }

    public static boolean canConfirmRoster(Status status, boolean closed, boolean confirmed) {
        return status == Status.SIGNUPS && closed && !confirmed;
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    public static Prep of(Facts f) {
        List<Step> steps = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean managed = f.mode() != Mode.OFF;
        boolean started = f.status() == Status.RUNNING || f.status() == Status.SCORING || f.status() == Status.DONE;
        boolean called = f.status() == Status.CANCELLED;

        // 1. build
        if (f.hasObjectives()) {
            steps.add(new Step("build", "Build the event", State.DONE, true, "The objectives are set.", List.of()));
        } else {
            blockers.add("Add at least one objective.");
            steps.add(new Step("build", "Build the event", called ? State.SKIPPED : State.ACTIVE, true, "Add at least one objective.", List.of("Add at least one objective.")));
        }

        // 2. signups
        String people = plural(f.active(), "player", "players") + (f.waiting() > 0 ? " and " + f.waiting() + " waiting" : "");
        if (started || f.signupsClosed()) {
            steps.add(new Step("signups", "Signups", State.DONE, managed, started && !f.signupsClosed() ? "Signups ended when the event started." : "Closed. " + people + " on the roster.", List.of()));
        } else if (called) {
            steps.add(new Step("signups", "Signups", State.SKIPPED, managed, "The event was cancelled.", List.of()));
        } else if (f.status() == Status.SIGNUPS) {
            List<String> why = managed ? List.of("Close signups.") : List.of();
            blockers.addAll(why);
            steps.add(new Step("signups", "Signups", State.ACTIVE, managed, "Open: " + people + " so far. Close signups when you have everyone.", why));
        } else {
            List<String> why = managed ? List.of("Open signups, then close them.") : List.of();
            blockers.addAll(why);
            steps.add(new Step("signups", "Signups", State.WAITING, managed, "Open signups when the event is ready.", why));
        }

        // 3. the roster
        if (started || f.rosterConfirmed()) {
            steps.add(new Step("roster", "Roster", State.DONE, managed, f.rosterConfirmed() ? "Confirmed: " + people + "." : "Not confirmed before the event started.", List.of()));
        } else if (called) {
            steps.add(new Step("roster", "Roster", State.SKIPPED, managed, "The event was cancelled.", List.of()));
        } else if (f.signupsClosed()) {
            List<String> why = managed ? List.of("Confirm the roster.") : List.of();
            blockers.addAll(why);
            steps.add(new Step("roster", "Roster", State.ACTIVE, managed, people + ". Check the list, then confirm it.", why));
        } else {
            steps.add(new Step("roster", "Roster", State.WAITING, managed, "Close signups first.", List.of()));
        }

        // 4. verification, only when the event asks for it
        if (f.verification().required()) {
            int unsettled = f.tally().unsettled();
            boolean blocksStart = f.verification().gate() == Gate.START;
            String detail = f.tally().settled() + " verified" + (f.tally().waived() > 0 ? " (" + f.tally().waived() + " waived)" : "")
                    + (f.tally().pending() > 0 ? ", " + f.tally().pending() + " waiting for a decision" : "")
                    + (f.tally().rejected() > 0 ? ", " + f.tally().rejected() + " turned down" : "")
                    + ". Players must pass " + switch (f.verification().gate()) {
                        case SIGNUP -> "to count from signup";
                        case TEAMS -> "before teams are made";
                        case START -> "before the event starts";
                    } + ".";
            List<String> why = new ArrayList<>();
            if (unsettled > 0 && !started && !called) {
                if (blocksStart) {
                    why.add(plural(unsettled, "player still needs", "players still need") + " verification: verify, waive or remove them.");
                    blockers.addAll(why);
                } else {
                    warnings.add(plural(unsettled, "player isn't", "players aren't") + " verified, so they won't count once the gate has passed. Verify, waive or remove them.");
                }
            }
            steps.add(new Step("verification", "Verification", unsettled == 0 ? State.DONE : called ? State.SKIPPED : State.ACTIVE, blocksStart, detail, why));
        }
        return new Prep(steps, blockers, warnings);
    }
}
