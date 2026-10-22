package com.younglings.bot.events;

import com.younglings.bot.events.TeamRules.Kind;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The waitlist for an event that is full. The host picks the order: first come first served, the host's clan members first (then first come first
 * served among everyone else), or round robin between the servers players came through (the host, then each co-host, in turn; T-220). When a place
 * frees up, the first waiting entry moves in. Pure, so it can be tested.
 * <p>
 * <b>Seat quotas</b> (T-220, {@code docs/EVENTS-CROSS-SERVER.md} section 4) work with any of those orders: a server with a quota has at most that many
 * places until the release time (24 hours before signups close by default, or when they close); a player beyond their server's quota waits. After the
 * release, free places go to the waitlist in the usual order, whoever they came through. A server without a quota is not limited. Staff adding someone
 * late ignore quotas.
 */
public final class QueueRules {
    public enum Policy { FIRST_COME, CLAN_FIRST, ROUND_ROBIN }

    /** Hours before signups close that unfilled quota places are released, unless the event says otherwise. */
    public static final int DEFAULT_RELEASE_HOURS = 24;
    /** The release can be set up to a week before signups close. */
    public static final int MAX_RELEASE_HOURS = 7 * 24;

    /** Someone asking to join: who, what kind of player, when, and the server they came through ({@code origin}; the host's id for the host). */
    public record Entry(long userId, Kind kind, Instant at, long origin) {
        public Entry(long userId, Kind kind, Instant at) {
            this(userId, kind, at, 0L);
        }
    }

    /**
     * The seat quotas in force for one event: places per server ({@code seats}; a server not in it is unlimited) and whether they have been released
     * (then nobody is limited).
     */
    public record Quotas(Map<Long, Integer> seats, boolean released) {
        public static final Quotas NONE = new Quotas(Map.of(), true);

        public Quotas {
            seats = seats == null ? Map.of() : Map.copyOf(seats);
        }

        /** The most places a server's players may hold now, or null when nothing limits them. */
        public Integer limitFor(long origin) {
            return released ? null : seats.get(origin);
        }

        /** Whether one more of {@code origin}'s players may take a place, given how many places each server holds now. */
        public boolean hasRoom(long origin, Map<Long, Integer> activeByOrigin) {
            Integer limit = limitFor(origin);
            return limit == null || activeByOrigin.getOrDefault(origin, 0) < limit;
        }
    }

    private QueueRules() {}

    /** The waiting entries in the order they will be let in. Round robin has no host here, so it starts with whichever server's player waited longest. */
    public static List<Entry> order(Policy policy, List<Entry> waiting) {
        return order(policy, 0L, waiting);
    }

    /** The waiting entries in the order they will be let in, for an event hosted by {@code host}. */
    public static List<Entry> order(Policy policy, long host, List<Entry> waiting) {
        Comparator<Entry> byTime = Comparator.comparing(Entry::at).thenComparingLong(Entry::userId);
        if (policy == Policy.ROUND_ROBIN) return roundRobin(host, waiting, byTime);
        Comparator<Entry> comparator = policy == Policy.CLAN_FIRST
                ? Comparator.comparing((Entry e) -> e.kind() == Kind.HOME_CLAN ? 0 : 1).thenComparing(byTime)
                : byTime;
        return waiting.stream().sorted(comparator).toList();
    }

    /**
     * Host, co-host A, co-host B, host, ...: one from each server in turn, each server's players in join order. The host goes first; the other servers
     * follow in the order their first player started waiting. A server with nobody left waiting is skipped.
     */
    private static List<Entry> roundRobin(long host, List<Entry> waiting, Comparator<Entry> byTime) {
        Map<Long, List<Entry>> byOrigin = new LinkedHashMap<>();
        List<Entry> sorted = waiting.stream().sorted(byTime).toList();
        if (sorted.stream().anyMatch(e -> e.origin() == host)) byOrigin.put(host, new ArrayList<>());
        for (Entry e : sorted) byOrigin.computeIfAbsent(e.origin(), k -> new ArrayList<>()).add(e);
        List<Entry> out = new ArrayList<>(waiting.size());
        for (int round = 0; out.size() < sorted.size(); round++) {
            for (List<Entry> queue : byOrigin.values()) if (round < queue.size()) out.add(queue.get(round));
        }
        return out;
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

    /**
     * Who moves in next when a place frees up: the first in line whose server still has room under its quota, or null when nobody waiting can take it
     * (everyone left is over their server's quota until the release).
     */
    public static Entry next(Policy policy, long host, List<Entry> waiting, Quotas quotas, Map<Long, Integer> activeByOrigin) {
        for (Entry e : order(policy, host, waiting)) {
            if (quotas.hasRoom(e.origin(), activeByOrigin)) return e;
        }
        return null;
    }

    /** Where this entry stands in the waitlist (1 is next), or 0 when it isn't waiting. */
    public static int position(Policy policy, List<Entry> waiting, long userId) {
        return position(policy, 0L, waiting, userId);
    }

    /** Where this entry stands in the waitlist of an event hosted by {@code host} (1 is next), or 0 when it isn't waiting. */
    public static int position(Policy policy, long host, List<Entry> waiting, long userId) {
        List<Entry> ordered = order(policy, host, waiting);
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).userId() == userId) return i + 1;
        return 0;
    }

    // ---------- quotas ----------

    /**
     * The host's own quota settings, from the event's settings {@code queueQuota: {host, releaseHours}}: the host server's seat quota (missing or 0 is
     * none) and how many hours before signups close unfilled quota places are released (24 unless set). Co-hosts' quotas are kept with the co-host.
     */
    public record QuotaSetting(Integer host, int releaseHours) {
        public static final QuotaSetting NONE = new QuotaSetting(null, DEFAULT_RELEASE_HOURS);
    }

    /** The quota settings in an event's settings; anything unreadable counts as not set ({@link #quotaSettingProblems} reports it). */
    public static QuotaSetting quotaSetting(net.dv8tion.jda.api.utils.data.DataObject config) {
        if (config == null || !config.hasKey("queueQuota") || config.isNull("queueQuota")) return QuotaSetting.NONE;
        try {
            var q = config.getObject("queueQuota");
            Integer host = q.hasKey("host") && !q.isNull("host") ? q.getInt("host") : null;
            int hours = q.hasKey("releaseHours") && !q.isNull("releaseHours") ? q.getInt("releaseHours") : DEFAULT_RELEASE_HOURS;
            return new QuotaSetting(host == null || host <= 0 ? null : host, Math.min(Math.max(hours, 0), MAX_RELEASE_HOURS));
        } catch (RuntimeException e) {
            return QuotaSetting.NONE;
        }
    }

    /** What is wrong with {@code queueQuota} on its own (the sum against the player limit is checked where the co-hosts are known). */
    public static List<String> quotaSettingProblems(net.dv8tion.jda.api.utils.data.DataObject config) {
        List<String> problems = new ArrayList<>();
        if (config == null || !config.hasKey("queueQuota") || config.isNull("queueQuota")) return problems;
        try {
            var q = config.getObject("queueQuota");
            if (q.hasKey("host") && !q.isNull("host") && q.getInt("host") < 0) problems.add("Your server's seat quota can't be negative. Use 0 for no quota.");
            if (q.hasKey("releaseHours") && !q.isNull("releaseHours")) {
                int hours = q.getInt("releaseHours");
                if (hours < 0 || hours > MAX_RELEASE_HOURS) problems.add("Release unfilled quota places 0 to " + MAX_RELEASE_HOURS + " hours before signups close.");
            }
        } catch (RuntimeException e) {
            problems.add("The seat quota settings couldn't be read.");
        }
        return problems;
    }

    /**
     * When unfilled quota places are released: {@code releaseHours} before signups close, or null when there is no deadline (then they are released
     * when signups close).
     */
    public static Instant releaseAt(Instant signupsCloseAt, int releaseHours) {
        return signupsCloseAt == null ? null : signupsCloseAt.minus(Duration.ofHours(Math.max(0, releaseHours)));
    }

    /** Whether quota places are released at {@code now}: signups have closed, or the release time has passed. */
    public static boolean released(Instant now, Instant signupsCloseAt, boolean signupsClosed, int releaseHours) {
        if (signupsClosed) return true;
        Instant at = releaseAt(signupsCloseAt, releaseHours);
        return at != null && !now.isBefore(at);
    }

    /**
     * What is wrong with a set of quotas, or null. Quotas need a player limit, and together they can't be more than it; each is at least 1. A null
     * quota means "no quota" for that server.
     */
    public static String quotaProblem(int maxParticipants, Integer hostQuota, Collection<Integer> cohostQuotas) {
        List<Integer> all = new ArrayList<>();
        if (hostQuota != null) all.add(hostQuota);
        for (Integer q : cohostQuotas) if (q != null) all.add(q);
        if (all.isEmpty()) return null;
        if (maxParticipants <= 0) return "Seat quotas need a player limit. Set the most players first.";
        if (all.stream().anyMatch(q -> q < 1)) return "A seat quota is at least 1 place. Leave it empty for no quota.";
        int sum = all.stream().mapToInt(Integer::intValue).sum();
        if (sum > maxParticipants) return "The seat quotas add up to " + sum + ", more than the " + maxParticipants + " players the event takes.";
        return null;
    }
}
