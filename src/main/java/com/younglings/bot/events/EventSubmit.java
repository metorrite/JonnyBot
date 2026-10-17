package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Standing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The rules behind {@code /submit}, kept free of Discord so they can be tested: which event a command belongs to (the channel it is used in, or the
 * event number the player gave), whether this person may send proof from here, where the receipt goes, and which tiles a player can still send proof for.
 */
public final class EventSubmit {
    private EventSubmit() {}

    /** An event channel a command was used in: the event it belongs to and its key ({@code general}, {@code team:7}, {@code player:123}, ...). */
    public record Channel(long eventId, String key) {}

    /** Where the command was used, from the submitter's point of view. */
    public enum Place { OWN_CHANNEL, ANOTHER_PLAYERS, ANOTHER_TEAMS, EVENT_CHANNEL, ELSEWHERE }

    /** The event a command is about, or why there isn't one. {@code place} is how the channel relates to that event. */
    public record Route(Long eventId, Place place, String refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    /**
     * Which event and place a command belongs to. An event number the player gave wins; otherwise the event of the channel. A channel of another event
     * than the one named counts as "elsewhere".
     */
    public static Route route(Channel channel, Long explicitEvent, long userId, Long teamId) {
        Long eventId = explicitEvent != null ? explicitEvent : channel == null ? null : channel.eventId();
        if (eventId == null) return new Route(null, Place.ELSEWHERE, "Use this in your event's channel, or give the event's number (`/events mine` lists yours).");
        Channel here = channel != null && channel.eventId() == eventId ? channel : null;
        return new Route(eventId, placeOf(here, userId, teamId), null);
    }

    /** What a channel key means for this player: their own player or team channel, someone else's, a shared channel of the event, or no event channel at all. */
    public static Place placeOf(Channel channel, long userId, Long teamId) {
        if (channel == null || channel.key() == null) return Place.ELSEWHERE;
        String key = channel.key();
        if (key.startsWith("player:")) return idOf(key, "player:") == userId ? Place.OWN_CHANNEL : Place.ANOTHER_PLAYERS;
        if (key.startsWith("team:")) return teamId != null && idOf(key, "team:") == teamId ? Place.OWN_CHANNEL : Place.ANOTHER_TEAMS;
        return Place.EVENT_CHANNEL;
    }

    private static long idOf(String key, String prefix) {
        try {
            return Long.parseLong(key.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Why this person can't send proof from {@code place}, or null when they can. Staff and the event's organisers may send from anywhere (for testing and
     * for a player who can't); a player may not use someone else's channel, and when the event requires submissions to come from its channels they may not
     * send from outside them.
     */
    public static String refusal(Place place, boolean requireChannel, boolean manages) {
        if (manages) return null;
        return switch (place) {
            case ANOTHER_PLAYERS -> "That's another player's channel. Use `/submit` in your own channel.";
            case ANOTHER_TEAMS -> "That's another team's channel. Use `/submit` in your team's channel.";
            case ELSEWHERE -> requireChannel ? "This event only takes proof sent from its own channels. Use `/submit` in your channel for the event." : null;
            case OWN_CHANNEL, EVENT_CHANNEL -> null;
        };
    }

    /**
     * The channel key where a receipt for this player goes: their team's channel in a team event, otherwise their own channel, whichever exists among
     * {@code existing}; null when the event has neither (the receipt is then only shown to the player).
     */
    public static String receiptKey(boolean teamScored, Long teamId, long userId, Collection<String> existing) {
        if (teamScored && teamId != null && existing.contains("team:" + teamId)) return "team:" + teamId;
        if (existing.contains("player:" + userId)) return "player:" + userId;
        if (!teamScored && teamId != null && existing.contains("team:" + teamId)) return "team:" + teamId;
        return null;
    }

    /**
     * The objectives a player can still send proof for: those that accept proof, whose prerequisites are done and that haven't reached their repeat
     * limit for them (or their team). {@code mine} is their standing, null when they have no progress yet.
     */
    public static List<ObjectiveSpec> openObjectives(List<ObjectiveSpec> objectives, Standing mine) {
        Map<String, Integer> done = mine == null ? Map.of() : mine.completions();
        List<ObjectiveSpec> open = new ArrayList<>();
        for (ObjectiveSpec o : objectives) {
            if (!EventRules.evidenceAllows(o.evidence(), EventRules.Source.CLAIM)) continue;
            if (done.getOrDefault(o.key(), 0) >= o.repeatLimit()) continue;
            if (o.prerequisites().stream().anyMatch(p -> done.getOrDefault(p, 0) < 1)) continue;
            open.add(o);
        }
        return open;
    }

    /** The objective a typed tile name means: an exact label (ignoring case), else the only label containing the text; null when it is unclear. */
    public static ObjectiveSpec byLabel(List<ObjectiveSpec> objectives, String typed) {
        if (typed == null || typed.isBlank()) return null;
        String wanted = typed.strip().toLowerCase();
        for (ObjectiveSpec o : objectives) if (o.label().strip().toLowerCase().equals(wanted)) return o;
        for (ObjectiveSpec o : objectives) if (o.key().equals(typed.strip())) return o;
        List<ObjectiveSpec> contains = objectives.stream().filter(o -> o.label().toLowerCase().contains(wanted)).toList();
        return contains.size() == 1 ? contains.get(0) : null;
    }
}
