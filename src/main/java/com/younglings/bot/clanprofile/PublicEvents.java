package com.younglings.bot.clanprofile;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Which of a clan's Discord events belong in the public feed: only ones the clan chose to share, that haven't finished or been
 * cancelled, soonest first. Kept apart from Discord and the database so the rule can be tested.
 */
public final class PublicEvents {
    /** The facts of an event that are safe to show publicly. {@code status} is Discord's: SCHEDULED, ACTIVE, COMPLETED or CANCELED. */
    public record EventInfo(long id, String name, String description, String imageUrl, String location, String status, Instant start, Instant end,
                            int interested, String url) {}

    private PublicEvents() {}

    /** Whether the event is still to come or happening now. */
    public static boolean upcoming(EventInfo event, Instant now) {
        if (!event.status().equals("SCHEDULED") && !event.status().equals("ACTIVE")) return false;
        Instant over = event.end() != null ? event.end() : event.start().plusSeconds(6 * 3600);
        return over.isAfter(now);
    }

    /** The shared, upcoming events, soonest first, at most {@code limit}. */
    public static List<EventInfo> feed(List<EventInfo> events, java.util.Set<Long> shared, Instant now, int limit) {
        return events.stream().filter(e -> shared.contains(e.id())).filter(e -> upcoming(e, now))
                .sorted(Comparator.comparing(EventInfo::start).thenComparingLong(EventInfo::id)).limit(Math.max(0, limit)).toList();
    }
}
