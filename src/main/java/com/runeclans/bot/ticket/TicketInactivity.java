package com.runeclans.bot.ticket;

import java.time.Duration;
import java.time.Instant;

/**
 * When an open ticket has gone quiet: a server can have tickets close themselves after a number of days with no message. The ticket is
 * warned a day before (by a message that says it will close unless someone replies), and any reply, even the requester's "still here",
 * cancels it because it becomes the new last message.
 */
public final class TicketInactivity {
    public static final int MAX_DAYS = 90;
    /** How long after the warning the ticket closes if nobody has replied. */
    public static final Duration GRACE = Duration.ofHours(24);
    /** A ticket is never warned before it has been quiet this long, so a one-day setting still gives people half a day. */
    static final Duration MIN_QUIET_BEFORE_WARNING = Duration.ofHours(12);
    /** The start of the warning, which is how the warning is recognised as the last message. */
    public static final String WARNING_MARKER = "⏳ This ticket has been quiet";

    public enum Action { NOTHING, WARN, CLOSE }

    private TicketInactivity() {}

    public static boolean validDays(Integer days) {
        return days == null || (days >= 1 && days <= MAX_DAYS);
    }

    /**
     * What to do with a ticket whose last message was at {@code lastMessageAt}. {@code lastIsWarning} says that last message is our own
     * warning, in which case the clock for closing runs from the warning.
     */
    public static Action decide(Instant lastMessageAt, boolean lastIsWarning, Instant now, int days) {
        Duration quiet = Duration.between(lastMessageAt, now);
        if (lastIsWarning) return quiet.compareTo(GRACE) >= 0 ? Action.CLOSE : Action.NOTHING;
        Duration warnAfter = Duration.ofDays(days).minus(GRACE);
        if (warnAfter.compareTo(MIN_QUIET_BEFORE_WARNING) < 0) warnAfter = MIN_QUIET_BEFORE_WARNING;
        return quiet.compareTo(warnAfter) >= 0 ? Action.WARN : Action.NOTHING;
    }

    /** The words of the warning, naming when the ticket will close. */
    public static String warningText(Instant closesAt) {
        return WARNING_MARKER + ". It will close automatically <t:" + closesAt.getEpochSecond() + ":R> unless someone replies.";
    }

    /** The close reason recorded on the ticket. */
    public static String closeReason(int days) {
        return "Closed automatically after " + days + (days == 1 ? " day" : " days") + " without a message";
    }
}
