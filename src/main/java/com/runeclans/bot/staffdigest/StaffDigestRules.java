package com.runeclans.bot.staffdigest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The weekly staff digest (T-193), the pure part: what goes in it and when it is due. The facts come from data the dashboard already shows
 * (Needs attention, Tickets, the server's Discord events); this only turns them into the post's lines.
 */
public final class StaffDigestRules {
    private StaffDigestRules() {}

    /** Events listed at most; the rest are counted. */
    public static final int MAX_EVENTS = 10;

    /** A Discord event starting in the next seven days. */
    public record EventLine(String name, OffsetDateTime start) {}

    /**
     * What the digest reports. {@code rosterSize} 0 means no clan roster (the clan line is left out); {@code openTickets} null means the server
     * has never used tickets (the ticket line is left out).
     */
    public record Facts(int rosterSize, int inactive, int unverified, int promotionsDue, int stale, Integer openTickets, Integer escalated, List<EventLine> events) {}

    /** The post's lines, in order: title, clan, tickets, coming up, footer. Event times are Discord timestamps, so each reader sees their own time. */
    public static List<String> lines(Facts f) {
        List<String> out = new ArrayList<>();
        out.add("### 📋 Staff digest");
        if (f.rosterSize() > 0) {
            out.add("**Clan** · " + f.rosterSize() + " members · " + count(f.inactive(), "inactive 30+ days") + " · " + count(f.unverified(), "unverified")
                    + " · " + count(f.promotionsDue(), "promotion due", "promotions due") + (f.stale() > 0 ? " · " + f.stale() + " not refreshed lately" : ""));
        }
        if (f.openTickets() != null) {
            out.add("**Tickets** · " + count(f.openTickets(), "open") + (f.escalated() != null && f.escalated() > 0 ? " (" + f.escalated() + " escalated)" : ""));
        }
        StringBuilder events = new StringBuilder("**Coming up (7 days)**");
        if (f.events().isEmpty()) {
            events.append(" · nothing scheduled");
        } else {
            for (EventLine e : f.events().subList(0, Math.min(MAX_EVENTS, f.events().size()))) {
                events.append("\n- ").append(e.name()).append(" · <t:").append(e.start().toEpochSecond()).append(":f>");
            }
            if (f.events().size() > MAX_EVENTS) events.append("\n-# …and ").append(f.events().size() - MAX_EVENTS).append(" more.");
        }
        out.add(events.toString());
        out.add("-# The details are on the dashboard: Needs attention, Tickets and Events.");
        return out;
    }

    private static String count(int n, String word) {
        return n + " " + word;
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Whether the weekly post is due at {@code now} (UTC): the chosen weekday, at or after the chosen hour, and not already posted today. */
    public static boolean due(LocalDateTime now, DayOfWeek weekday, int hour, LocalDate lastPostedOn) {
        return now.getDayOfWeek() == weekday && now.getHour() >= hour && !now.toLocalDate().equals(lastPostedOn);
    }
}
