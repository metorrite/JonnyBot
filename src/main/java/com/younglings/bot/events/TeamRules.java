package com.younglings.bot.events;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Who may be in an event and who may share a team, chosen per event. A player is one of three kinds relative to the hosting server, and the
 * host decides which kinds may join and which kinds may be on the same team (mixing groups). Pure, so it can be tested.
 */
public final class TeamRules {
    /** A player's relationship to the host: in the host's in-game clan, in the host's server but not the clan, or from another (partner) server. */
    public enum Kind { HOME_CLAN, HOME_OTHER, PARTNER }

    /**
     * {@code allowed}: the kinds that may join at all. {@code groups}: kinds in the same group may share a team; kinds in different groups
     * may not. A kind in no group can only be on a team with its own kind.
     */
    public record Policy(Set<Kind> allowed, List<Set<Kind>> groups) {
        public Policy {
            allowed = Set.copyOf(allowed);
            groups = groups.stream().map(Set::copyOf).toList();
        }
    }

    private TeamRules() {}

    /** Everyone who may join may share a team. */
    public static Policy everyoneTogether() {
        return new Policy(EnumSet.allOf(Kind.class), List.of(EnumSet.allOf(Kind.class)));
    }

    /** Only the host's own server (clan or not) may join, and they mix freely. */
    public static Policy homeServerOnly() {
        return new Policy(EnumSet.of(Kind.HOME_CLAN, Kind.HOME_OTHER), List.of(EnumSet.of(Kind.HOME_CLAN, Kind.HOME_OTHER)));
    }

    /** Everyone may join, but clan members only team with clan members, and everyone else with each other. */
    public static Policy clanVersusOutsiders() {
        return new Policy(EnumSet.allOf(Kind.class), List.of(EnumSet.of(Kind.HOME_CLAN), EnumSet.of(Kind.HOME_OTHER, Kind.PARTNER)));
    }

    /** Everyone may join and every kind stays on its own teams. */
    public static Policy allSeparate() {
        return new Policy(EnumSet.allOf(Kind.class), List.of(EnumSet.of(Kind.HOME_CLAN), EnumSet.of(Kind.HOME_OTHER), EnumSet.of(Kind.PARTNER)));
    }

    /** What is wrong with a policy, in words, or an empty list. */
    public static List<String> problems(Policy policy) {
        List<String> problems = new ArrayList<>();
        if (policy.allowed().isEmpty()) problems.add("Choose at least one kind of player who may join.");
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        for (Set<Kind> group : policy.groups()) {
            for (Kind k : group) {
                if (!seen.add(k)) problems.add("A kind of player can only be in one mixing group.");
                if (!policy.allowed().contains(k)) problems.add("A mixing group includes a kind of player who isn't allowed to join.");
            }
        }
        return problems.stream().distinct().toList();
    }

    public static boolean mayJoin(Policy policy, Kind kind) {
        return policy.allowed().contains(kind);
    }

    /** Whether a player of {@code kind} may be put on a team whose current members are the given kinds. */
    public static boolean mayShareTeam(Policy policy, Kind kind, List<Kind> teammates) {
        if (!mayJoin(policy, kind)) return false;
        for (Kind other : teammates) {
            if (other == kind) continue;
            if (policy.groups().stream().noneMatch(g -> g.contains(kind) && g.contains(other))) return false;
        }
        return true;
    }
}
