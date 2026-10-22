package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Status;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Locale;

/**
 * Who sees an event's standings (T-211), from {@code config.standings.visibility}. Staff always see them on the dashboard; this decides what players see on
 * the website's event page and on the board in Discord. A player always sees their own (or their team's) points; what can be hidden is the table and the
 * ranks. Pure, so it can be tested.
 */
public final class EventStandings {
    public enum Visibility {
        /** Everyone taking part (today's behaviour, the default). */
        PARTICIPANTS,
        /** Everyone in the server who can see the event (and, with the hub switch, the events hub). */
        SERVER,
        /** Only staff and organisers. */
        STAFF,
        /** Nobody but staff until the event ends, then like {@link #PARTICIPANTS}. */
        HIDDEN_UNTIL_END
    }

    public static final Visibility DEFAULT = Visibility.PARTICIPANTS;

    private EventStandings() {}

    public static Visibility of(DataObject config) {
        try {
            if (config == null || !config.hasKey("standings") || config.isNull("standings")) return DEFAULT;
            DataObject s = config.getObject("standings");
            if (!s.hasKey("visibility") || s.isNull("visibility")) return DEFAULT;
            return Visibility.valueOf(s.getString("visibility").strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    /** Whether players are shown the table and the ranks at {@code status}. */
    public static boolean tableShown(Visibility visibility, Status status) {
        return switch (visibility) {
            case PARTICIPANTS, SERVER -> true;
            case STAFF -> false;
            case HIDDEN_UNTIL_END -> status == Status.SCORING || status == Status.DONE;
        };
    }

    /** What players are told when the table is hidden from them. */
    public static String hiddenText(Visibility visibility) {
        return visibility == Visibility.HIDDEN_UNTIL_END ? "The standings are hidden until the event ends." : "The standings are only shown to staff.";
    }
}
