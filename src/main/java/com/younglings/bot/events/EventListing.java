package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.Status;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;

/**
 * Which engine events the events hub lists (T-216, design {@code docs/EVENTS-HUB.md}) and what of them it may show. Only what the host chose: an event is
 * listed while its visibility is "This server and its alliances" ({@link Scope#ALLIANCE}) or "Open to everyone" ({@link Scope#PUBLIC}), it isn't a draft,
 * cancelled, archived or taken down by JonnyBot staff, and (once finished) for two weeks after it ended. Never a player's name or Discord id. Pure.
 */
public final class EventListing {
    /** A finished event stays on the hub this long after it ended, so players can see how it went. */
    public static final Duration FINISHED_SHOWN = Duration.ofDays(14);
    public static final int SHORT_DESCRIPTION = 300;

    /** Who a listed event is shown to. */
    public enum Access {
        /** Open to everyone: anyone who can see the hub. */
        PUBLIC,
        /** Members of a server allied with the host. */
        ALLIANCE,
        /** Members of the host server (their own server's events, whatever the visibility). */
        MINE
    }

    private EventListing() {}

    /** Whether the event belongs on the hub at all, before asking who is looking. */
    public static boolean listed(Event e, Instant now) {
        if (e.scope() == Scope.PRIVATE || e.archived()) return false;
        if (e.status() == Status.DRAFT || e.status() == Status.CANCELLED) return false;
        if (takenDown(config(e))) return false;
        return e.status() != Status.DONE || e.endsAt() == null || e.endsAt().isAfter(now.minus(FINISHED_SHOWN));
    }

    /**
     * Whether someone sees a listed event: everyone sees an open one, members of an allied server see an alliance one. {@code allied} says whether the viewer
     * is in a server allied with the host.
     */
    public static boolean visible(Event e, boolean allied) {
        return e.scope() == Scope.PUBLIC || (e.scope() == Scope.ALLIANCE && allied);
    }

    /** Whether JonnyBot staff took the event off the hub (T-218), from {@code config.hub.takedown}. */
    public static boolean takenDown(DataObject config) {
        DataObject hub = hub(config);
        return hub != null && hub.hasKey("takedown") && !hub.isNull("takedown");
    }

    /** Why it was taken down, for the host's dashboard, or null. */
    public static String takedownReason(DataObject config) {
        DataObject hub = hub(config);
        if (hub == null || !hub.hasKey("takedown") || hub.isNull("takedown")) return null;
        try {
            return hub.getObject("takedown").getString("reason", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Whether the host allowed the standings on the hub ({@code config.hub.publicStandings}, off unless switched on). */
    public static boolean publicStandings(DataObject config) {
        DataObject hub = hub(config);
        return hub != null && hub.opt("publicStandings").orElse(null) instanceof Boolean b && b;
    }

    /** Whether the standings may be shown on the hub at all: the host switched it on and the event shows them to the whole server. */
    public static boolean standingsShown(Event e) {
        DataObject config = config(e);
        return publicStandings(config) && "SERVER".equals(standingsVisibility(config));
    }

    /**
     * Whether a clan-vs-clan event's clan standings may be shown on the hub (T-223): it is open to everyone, and its standings aren't kept to staff or
     * hidden until the end while it runs. Clan names only, so the host's "show the standings on the hub" switch (which is about players) isn't needed.
     */
    public static boolean clanStandingsShown(Event e) {
        if (!ClanVsClan.is(e.template()) || e.scope() != Scope.PUBLIC) return false;
        String visibility = standingsVisibility(config(e));
        if ("STAFF".equals(visibility)) return false;
        return !"HIDDEN_UNTIL_END".equals(visibility) || e.status() == Status.DONE;
    }

    /** {@code config.standings.visibility} as written (T-211), or null; read here directly so the hub doesn't depend on more than the setting's name. */
    private static String standingsVisibility(DataObject config) {
        try {
            if (config == null || !config.hasKey("standings") || config.isNull("standings")) return null;
            DataObject s = config.getObject("standings");
            return s.hasKey("visibility") && !s.isNull("visibility") ? s.getString("visibility").strip().toUpperCase(java.util.Locale.ROOT) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The first {@value #SHORT_DESCRIPTION} characters of the description, cut at a word where possible. */
    public static String shortDescription(String description) {
        if (description == null) return "";
        String text = description.strip();
        if (text.length() <= SHORT_DESCRIPTION) return text;
        int cut = text.lastIndexOf(' ', SHORT_DESCRIPTION);
        return text.substring(0, cut > SHORT_DESCRIPTION / 2 ? cut : SHORT_DESCRIPTION).strip() + "…";
    }

    private static DataObject config(Event e) {
        try {
            return DataObject.fromJson(e.config());
        } catch (RuntimeException ex) {
            return DataObject.empty();
        }
    }

    private static DataObject hub(DataObject config) {
        try {
            return config == null || !config.hasKey("hub") || config.isNull("hub") ? null : config.getObject("hub");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
