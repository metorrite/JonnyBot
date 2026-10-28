package com.runeclans.bot.events;

import java.util.List;

/**
 * Who counts as being in an event when the event asks for verification: proof that a player may take part (for instance screenshots of what
 * they hold, sent before the event so nobody saves up drops to open at the start) which an organiser approves. Verification is switched on per
 * event, and its {@link Gate} says at which moment a player who hasn't passed it stops counting: when signing up, when teams are made, or when the
 * event starts. The gate can be moved while the event is being set up, which is why nothing else stores it: every question about who counts is
 * answered here from a player's state, the settings and the moment being asked about. Pure, so it can be tested.
 */
public final class EventRoster {
    public static final int MAX_INSTRUCTIONS = 1000;

    /**
     * Where a player stands. {@code NOT_REQUIRED} is what everyone is when the event doesn't ask for verification; {@code PENDING} means no decision yet;
     * {@code WAIVED} is an organiser letting someone through without proof.
     */
    public enum Verification { NOT_REQUIRED, PENDING, VERIFIED, REJECTED, WAIVED }

    /** The moments in an event's life at which players are counted, in the order they happen. Also the moment a player must be verified by. */
    public enum Gate { SIGNUP, TEAMS, START }

    /** The event's verification settings: whether it asks, by when a player must have passed, and what players are told to send. */
    public record Setting(boolean required, Gate gate, String instructions) {
        public Setting {
            gate = gate == null ? Gate.TEAMS : gate;
            instructions = instructions == null ? "" : instructions.strip();
        }
    }

    /** What nearly every event has: no verification. */
    public static Setting notRequired() {
        return new Setting(false, Gate.TEAMS, "");
    }

    private EventRoster() {}

    /** A player's state as it reads today: without verification everyone is {@code NOT_REQUIRED}, and a player nobody has decided about is {@code PENDING}. */
    public static Verification effective(Verification stored, Setting setting) {
        if (!setting.required()) return Verification.NOT_REQUIRED;
        return stored == null || stored == Verification.NOT_REQUIRED ? Verification.PENDING : stored;
    }

    /**
     * Whether a player counts at {@code moment}. Everyone counts when nothing is required; a verified or waived player always counts; one who was turned
     * down never does; one still pending counts until the gate's moment arrives and not from then on.
     */
    public static boolean counts(Verification stored, Setting setting, Gate moment) {
        if (!setting.required()) return true;
        return switch (effective(stored, setting)) {
            case VERIFIED, WAIVED, NOT_REQUIRED -> true;
            case REJECTED -> false;
            case PENDING -> moment.ordinal() < setting.gate().ordinal();
        };
    }

    /** How many players are in each state, by how they read today. {@code settled} is those verified or waived. */
    public record Tally(int verified, int waived, int pending, int rejected) {
        public int settled() {
            return verified + waived;
        }

        /** Players who are neither verified nor waived: the ones an organiser still has to decide about (or remove). */
        public int unsettled() {
            return pending + rejected;
        }
    }

    public static Tally tally(List<Verification> stored, Setting setting) {
        int verified = 0;
        int waived = 0;
        int pending = 0;
        int rejected = 0;
        for (Verification v : stored) {
            switch (effective(v, setting)) {
                case VERIFIED -> verified++;
                case WAIVED -> waived++;
                case PENDING -> pending++;
                case REJECTED -> rejected++;
                case NOT_REQUIRED -> { }
            }
        }
        return new Tally(verified, waived, pending, rejected);
    }

    /** The states an organiser can put a player in. {@code NOT_REQUIRED} is never chosen: it is what the event being without verification looks like. */
    public static boolean settable(Verification to) {
        return to != null && to != Verification.NOT_REQUIRED;
    }

    /** What an organiser tells a player who is waiting, as one sentence (the instructions if there are any). */
    public static String askText(Setting setting) {
        return setting.instructions().isEmpty() ? "Send the verification the organisers asked for." : setting.instructions();
    }
}
