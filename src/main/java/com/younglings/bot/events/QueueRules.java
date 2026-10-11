package com.younglings.bot.events;

import com.younglings.bot.events.TeamRules.Kind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The waitlist for an event that is full. The host picks the order: first come first served, or the host's clan members first (then first
 * come first served among everyone else). When a place frees up, the first waiting entry moves in. Pure, so it can be tested.
 */
public final class QueueRules {
    public enum Policy { FIRST_COME, CLAN_FIRST }

    /** Someone asking to join: who, what kind of player, and when. */
    public record Entry(long userId, Kind kind, Instant at) {}

    private QueueRules() {}

    /** The waiting entries in the order they will be let in. */
    public static List<Entry> order(Policy policy, List<Entry> waiting) {
        Comparator<Entry> byTime = Comparator.comparing(Entry::at).thenComparingLong(Entry::userId);
        Comparator<Entry> comparator = policy == Policy.CLAN_FIRST
                ? Comparator.comparing((Entry e) -> e.kind() == Kind.HOME_CLAN ? 0 : 1).thenComparing(byTime)
                : byTime;
        return waiting.stream().sorted(comparator).toList();
    }

    /** Splits everyone who asked into those with a place and those waiting, for an event with {@code capacity} places (0 means no limit). */
    public static Split split(Policy policy, int capacity, List<Entry> everyone) {
        if (capacity <= 0) return new Split(order(FIRST_BY_TIME, everyone), List.of());
        List<Entry> ordered = order(policy, everyone);
        int places = Math.min(capacity, ordered.size());
        return new Split(new ArrayList<>(ordered.subList(0, places)), new ArrayList<>(ordered.subList(places, ordered.size())));
    }

    private static final Policy FIRST_BY_TIME = Policy.FIRST_COME;

    /** Who has a place ({@code active}) and who is waiting, in the order each will be reached. */
    public record Split(List<Entry> active, List<Entry> waiting) {}

    /** Who moves in next when a place frees up, or null when nobody is waiting. */
    public static Entry next(Policy policy, List<Entry> waiting) {
        List<Entry> ordered = order(policy, waiting);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /** Where this entry stands in the waitlist (1 is next), or 0 when it isn't waiting. */
    public static int position(Policy policy, List<Entry> waiting, long userId) {
        List<Entry> ordered = order(policy, waiting);
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).userId() == userId) return i + 1;
        return 0;
    }
}
