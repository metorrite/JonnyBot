package com.younglings.bot.rolerules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The decisions of connected and trigger roles that need neither Discord nor a database, so they can be tested on their own. */
public final class RoleRuleRules {
    public static final int MAX_RULES_PER_SERVER = 50;
    public static final int MAX_WHEN_ROLES = 5;
    public static final int MAX_THEN_ROLES = 10;
    /** How many rule-made changes may follow each other (a rule's change setting off another rule) before the chain is cut. */
    public static final int MAX_CHAIN_DEPTH = 3;
    /** Rule-made changes allowed per member per minute, whatever the rules say. */
    public static final int MAX_CHANGES_PER_MEMBER_PER_MINUTE = 10;

    private RoleRuleRules() {}

    /** GAINS: fires when the member comes to hold all the {@code when} roles. LOSES: fires when they stop holding all of them. */
    public enum Event {
        GAINS, LOSES;

        public static java.util.Optional<Event> ofKey(String key) {
            for (Event event : values()) if (event.name().equalsIgnoreCase(key)) return java.util.Optional.of(event);
            return java.util.Optional.empty();
        }
    }

    /** A rule: when a member {@code event}s all of {@code when}, add the {@code add} roles and take away the {@code remove} roles. */
    public record Rule(long id, long guildId, String name, Event event, List<Long> when, List<Long> add, List<Long> remove, boolean enabled) {}

    /** What a rule asks for on one member. */
    public record Change(Set<Long> add, Set<Long> remove) {
        public boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    /**
     * Whether a role change makes the rule fire. {@code heldAfter} is what the member holds after the change; {@code changed} are the roles that
     * just changed and {@code added} says whether they were added or removed. A rule fires only on the change that makes its condition become
     * true (GAINS) or stop being true (LOSES), never merely because the member happens to satisfy it, so it cannot fire over and over.
     */
    public static boolean fires(Rule rule, Set<Long> heldAfter, Set<Long> changed, boolean added) {
        if (!rule.enabled() || rule.when().isEmpty()) return false;
        boolean touches = rule.when().stream().anyMatch(changed::contains);
        if (!touches) return false;
        boolean holdsAllNow = heldAfter.containsAll(rule.when());
        if (rule.event() == Event.GAINS) return added && holdsAllNow;
        if (added) return false;
        Set<Long> heldBefore = new HashSet<>(heldAfter);
        heldBefore.addAll(changed);
        return !holdsAllNow && heldBefore.containsAll(rule.when());
    }

    /** What all the rules that fire ask for, minus what is pointless (adding a held role, removing one not held). Adds win over removes of the same role. */
    public static Change changesFor(List<Rule> rules, Set<Long> heldAfter, Set<Long> changed, boolean added) {
        Set<Long> add = new LinkedHashSet<>();
        Set<Long> remove = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (!fires(rule, heldAfter, changed, added)) continue;
            for (long role : rule.add()) if (!heldAfter.contains(role)) add.add(role);
            for (long role : rule.remove()) if (heldAfter.contains(role)) remove.add(role);
        }
        remove.removeAll(add);
        return new Change(add, remove);
    }

    /**
     * Roles that form a loop through the rules, or empty. Every rule gives an edge from each of its {@code when} roles to each role it adds or
     * removes; a cycle (A leads to B leads back to A, however many rules it takes, whether by adding or removing) is refused when saving, because
     * those rules could keep changing a member's roles forever. A rule whose own result is one of its own conditions is a cycle too.
     */
    public static List<Long> findCycle(List<Rule> rules) {
        Map<Long, Set<Long>> edges = new HashMap<>();
        for (Rule rule : rules) {
            if (!rule.enabled()) continue;
            for (long from : rule.when()) {
                edges.computeIfAbsent(from, k -> new HashSet<>()).addAll(rule.add());
                edges.get(from).addAll(rule.remove());
            }
        }
        Set<Long> done = new HashSet<>();
        for (Long start : edges.keySet()) {
            List<Long> path = new ArrayList<>();
            List<Long> cycle = walk(start, edges, new HashSet<>(), done, path);
            if (!cycle.isEmpty()) return cycle;
        }
        return List.of();
    }

    private static List<Long> walk(long node, Map<Long, Set<Long>> edges, Set<Long> onPath, Set<Long> done, List<Long> path) {
        if (onPath.contains(node)) {
            List<Long> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            cycle.add(node);
            return cycle;
        }
        if (done.contains(node)) return List.of();
        onPath.add(node);
        path.add(node);
        for (long next : edges.getOrDefault(node, Set.of())) {
            List<Long> cycle = walk(next, edges, onPath, done, path);
            if (!cycle.isEmpty()) return cycle;
        }
        path.remove(path.size() - 1);
        onPath.remove(node);
        done.add(node);
        return List.of();
    }

    /** Why a rule cannot be saved on its own (shape, sizes), or null. */
    public static String shapeProblem(Rule rule) {
        if (rule.name().isBlank()) return "Give the rule a name.";
        if (rule.name().length() > 60) return "Keep the name under 60 characters.";
        if (rule.when().isEmpty()) return "Choose at least one role that sets the rule off.";
        if (rule.when().size() > MAX_WHEN_ROLES) return "At most " + MAX_WHEN_ROLES + " roles can set a rule off.";
        if (rule.add().isEmpty() && rule.remove().isEmpty()) return "Choose at least one role to give or take away.";
        if (rule.add().size() > MAX_THEN_ROLES || rule.remove().size() > MAX_THEN_ROLES) return "At most " + MAX_THEN_ROLES + " roles to give and " + MAX_THEN_ROLES + " to take away.";
        if (rule.add().stream().anyMatch(rule.remove()::contains)) return "The same role cannot be both given and taken away.";
        if (rule.add().stream().anyMatch(rule.when()::contains) || rule.remove().stream().anyMatch(rule.when()::contains)) return "A rule cannot change a role that sets it off.";
        return null;
    }

    /** Whether a change that follows our own earlier change is still allowed: the chain is cut at {@link #MAX_CHAIN_DEPTH}. */
    public static boolean chainAllowed(int depth) {
        return depth < MAX_CHAIN_DEPTH;
    }
}
