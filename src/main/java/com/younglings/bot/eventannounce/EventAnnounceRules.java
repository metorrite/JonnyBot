package com.younglings.bot.eventannounce;

import java.util.Locale;

/**
 * The rules for announcing Discord's scheduled events: how an announcement reads, what the optional "interested" role is called, and when a role
 * may be made. Pure, so it can be tested; the service and listener only carry out what this decides.
 */
public final class EventAnnounceRules {
    private EventAnnounceRules() {}

    /** Discord allows 250 roles in a server. A role is made for an event only while the server has at least this much room left. */
    public static final int ROLE_LIMIT = 250;
    public static final int ROLE_HEADROOM = 20;
    public static final int MAX_DESCRIPTION = 700;
    public static final int MAX_ROLE_NAME = 100;

    /** Where an event stands, as Discord reports it. */
    public enum State { SCHEDULED, ACTIVE, COMPLETED, CANCELED }

    /** A server's settings: nothing is announced until it is on and has a channel. */
    public record Settings(long guildId, boolean enabled, Long channelId, boolean useRole, boolean notifyStart) {
        public static Settings defaults(long guildId) {
            return new Settings(guildId, false, null, false, true);
        }

        public boolean active() {
            return enabled && channelId != null;
        }
    }

    /** What an announcement says. {@code color} is an RGB value. */
    public record Card(String title, String description, String when, String where, String footer, int color, boolean open) {}

    /** Whether the server has room for one more event role (it stays well below Discord's 250). */
    public static boolean canMakeRole(int rolesInServer) {
        return rolesInServer < ROLE_LIMIT - ROLE_HEADROOM;
    }

    /** The role's name: "Event: name", cleaned of mentions and control characters, within 100 characters. */
    public static String roleName(String eventName) {
        String clean = eventName == null ? "" : eventName.replaceAll("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E]", "").replaceAll("[@#]", "").replaceAll("\\s+", " ").strip();
        String name = "Event: " + (clean.isEmpty() ? "event" : clean);
        return name.length() > MAX_ROLE_NAME ? name.substring(0, MAX_ROLE_NAME).strip() : name;
    }

    /** A Discord timestamp tag: {@code style} is F (full date and time) or R (relative). */
    public static String stamp(long epochSeconds, char style) {
        return "<t:" + epochSeconds + ":" + style + ">";
    }

    /**
     * The announcement for an event in its current state. A scheduled event is open (people can mark interest); an active one says it is on now; a
     * finished or cancelled one is shown as such and is no longer open. {@code location} is already worded (a channel mention, or the place typed in).
     */
    public static Card card(String name, String description, long startEpoch, Long endEpoch, String location, State state) {
        String when = stamp(startEpoch, 'F') + " (" + stamp(startEpoch, 'R') + ")";
        if (endEpoch != null) when += "\nUntil " + stamp(endEpoch, 'F');
        String text = description == null ? "" : description.strip();
        if (text.length() > MAX_DESCRIPTION) text = text.substring(0, MAX_DESCRIPTION).strip() + "…";
        String shownName = name == null || name.isBlank() ? "Event" : name.strip();
        return switch (state) {
            case SCHEDULED -> new Card("📅 " + shownName, text, when, location, "Press the button to be told when it starts.", 0x5865F2, true);
            case ACTIVE -> new Card("🔴 " + shownName + " is on now", text, when, location, "It has started.", 0x57F287, false);
            case COMPLETED -> new Card("✅ " + shownName, text, when, location, "This event has finished.", 0x747F8D, false);
            case CANCELED -> new Card("❌ " + shownName + " (cancelled)", text, when, location, "This event was cancelled.", 0xED4245, false);
        };
    }

    /** Whether an event in this state still needs its role. */
    public static boolean needsRole(State state) {
        return state == State.SCHEDULED || state == State.ACTIVE;
    }

    /** What to do when the state changes from {@code before} to {@code after}, once each. */
    public enum Step { NONE, START, END }

    public static Step step(State before, State after, boolean alreadyStarted) {
        if (after == State.COMPLETED || after == State.CANCELED) return before == State.COMPLETED || before == State.CANCELED ? Step.NONE : Step.END;
        if (after == State.ACTIVE && !alreadyStarted) return Step.START;
        return Step.NONE;
    }

    public static State state(String name) {
        try {
            return State.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return State.SCHEDULED;
        }
    }
}
