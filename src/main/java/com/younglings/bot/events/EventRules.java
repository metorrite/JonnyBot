package com.younglings.bot.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rules of the event engine that need no Discord and no database: what an event may be called, which stage it may move to, what an
 * objective is allowed to look like, and whether a set of objectives hangs together (unique keys, real prerequisites, no loops). Pure, so
 * it can be tested; the service applies it to what admins send.
 */
public final class EventRules {
    public static final int MAX_NAME = 80;
    public static final int MIN_NAME = 3;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_OBJECTIVES = 200;
    public static final int MAX_PARTICIPANTS = 1000;
    public static final int MAX_TEAMS = 100;
    public static final int MAX_TEAM_NAME = 40;
    public static final int MAX_LABEL = 80;
    public static final int MAX_OBJECTIVE_DESCRIPTION = 400;
    public static final long MAX_POINTS = 100_000;
    public static final long MAX_TARGET = 1_000_000;
    public static final int MAX_REPEAT = 1000;
    public static final int MAX_PREREQUISITES = 10;
    public static final int MAX_NOTE = 300;

    /** Draft: being built. Signups: players can join. Running: progress counts. Scoring: frozen while results are checked. Done and Cancelled are final. */
    public enum Status { DRAFT, SIGNUPS, RUNNING, SCORING, DONE, CANCELLED }

    /** Who may see an event: only the host server, servers in an alliance with it, or everyone. */
    public enum Scope { PRIVATE, ALLIANCE, PUBLIC }

    /** Whether standings add up per player or per team. */
    public enum ScoringMode { INDIVIDUAL, TEAM }

    /** How progress on an objective is recorded: by the tracked stats or plugin, by a proof an organiser approves, either of those, or only by an organiser. */
    public enum Evidence { AUTO, PROOF, EITHER, ORGANISER }

    /** Where a progress entry came from. */
    public enum Source { MANUAL, CLAIM, AUTO, IMPORT }

    /** One objective as admins define it. {@code target} is the amount that completes it once; it can count {@code repeatLimit} times. */
    public record ObjectiveSpec(String key, String label, String description, long points, Evidence evidence, long target, int repeatLimit, List<String> prerequisites) {
        public ObjectiveSpec {
            description = description == null ? "" : description;
            prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        }
    }

    private EventRules() {}

    // ---------- lifecycle ----------

    /** The stages an event may move to from {@code from}. Done and Cancelled are final; Cancelled is possible until Done. */
    public static Set<Status> next(Status from) {
        return switch (from) {
            case DRAFT -> Set.of(Status.SIGNUPS, Status.RUNNING, Status.CANCELLED);
            case SIGNUPS -> Set.of(Status.DRAFT, Status.RUNNING, Status.CANCELLED);
            case RUNNING -> Set.of(Status.SCORING, Status.CANCELLED);
            case SCORING -> Set.of(Status.RUNNING, Status.DONE, Status.CANCELLED);
            case DONE, CANCELLED -> Set.of();
        };
    }

    public static boolean canMove(Status from, Status to) {
        return next(from).contains(to);
    }

    /** Objectives, teams and settings may only be changed while the event is being built or taking signups. */
    public static boolean editable(Status status) {
        return status == Status.DRAFT || status == Status.SIGNUPS;
    }

    public static boolean acceptsJoins(Status status) {
        return status == Status.SIGNUPS || status == Status.RUNNING;
    }

    public static boolean acceptsProgress(Status status) {
        return status == Status.RUNNING;
    }

    // ---------- the event itself ----------

    public static List<String> eventProblems(String name, String description, int maxParticipants, Instant startsAt, Instant endsAt) {
        List<String> problems = new ArrayList<>();
        String n = name == null ? "" : name.strip();
        if (n.length() < MIN_NAME) problems.add("Give the event a name of at least " + MIN_NAME + " characters.");
        if (n.length() > MAX_NAME) problems.add("Keep the event name to " + MAX_NAME + " characters or fewer.");
        if (description != null && description.strip().length() > MAX_DESCRIPTION) problems.add("Keep the description to " + MAX_DESCRIPTION + " characters or fewer.");
        if (maxParticipants < 0 || maxParticipants > MAX_PARTICIPANTS) problems.add("The most players has to be between 0 (no limit) and " + MAX_PARTICIPANTS + ".");
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) problems.add("The event has to end after it starts.");
        return problems;
    }

    public static String teamNameProblem(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return "Give the team a name.";
        if (n.length() > MAX_TEAM_NAME) return "Keep the team name to " + MAX_TEAM_NAME + " characters or fewer.";
        return null;
    }

    // ---------- objectives ----------

    /** A key from a label: lower case words joined with dashes, at most 40 characters. */
    public static String keyFrom(String label) {
        String k = (label == null ? "" : label).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return k.length() > 40 ? k.substring(0, 40).replaceAll("-+$", "") : k;
    }

    public static boolean validKey(String key) {
        return key != null && key.matches("[a-z0-9]+(-[a-z0-9]+)*") && key.length() <= 40;
    }

    /** What is wrong with one objective on its own (not counting how it relates to the others). */
    public static List<String> objectiveProblems(ObjectiveSpec o) {
        List<String> problems = new ArrayList<>();
        String label = o.label() == null ? "" : o.label().strip();
        String name = label.isEmpty() ? "An objective" : "\"" + label + "\"";
        if (label.isEmpty()) problems.add("Every objective needs a title.");
        if (label.length() > MAX_LABEL) problems.add(name + ": keep the title to " + MAX_LABEL + " characters or fewer.");
        if (!validKey(o.key())) problems.add(name + ": the key has to be lower case words joined with dashes (like \"kill-zuk\").");
        if (o.description().length() > MAX_OBJECTIVE_DESCRIPTION) problems.add(name + ": keep the description to " + MAX_OBJECTIVE_DESCRIPTION + " characters or fewer.");
        if (o.points() < 0 || o.points() > MAX_POINTS) problems.add(name + ": points have to be between 0 and " + MAX_POINTS + ".");
        if (o.target() < 1 || o.target() > MAX_TARGET) problems.add(name + ": the amount needed has to be at least 1.");
        if (o.repeatLimit() < 1 || o.repeatLimit() > MAX_REPEAT) problems.add(name + ": how many times it can count has to be between 1 and " + MAX_REPEAT + ".");
        if (o.evidence() == null) problems.add(name + ": choose how progress is recorded.");
        if (o.prerequisites().size() > MAX_PREREQUISITES) problems.add(name + ": at most " + MAX_PREREQUISITES + " prerequisites.");
        if (o.prerequisites().contains(o.key())) problems.add(name + ": it can't require itself.");
        return problems;
    }

    /** What is wrong with a set of objectives as a whole: how many, unique keys, prerequisites that exist, and no loops. */
    public static List<String> objectiveSetProblems(List<ObjectiveSpec> objectives) {
        List<String> problems = new ArrayList<>();
        if (objectives.size() > MAX_OBJECTIVES) problems.add("An event can have at most " + MAX_OBJECTIVES + " objectives.");
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) {
            if (byKey.put(o.key(), o) != null) problems.add("Two objectives share the key \"" + o.key() + "\".");
        }
        for (ObjectiveSpec o : objectives) {
            for (String p : o.prerequisites()) {
                if (!byKey.containsKey(p)) problems.add("\"" + o.label() + "\" requires \"" + p + "\", which isn't an objective of this event.");
            }
        }
        if (problems.isEmpty() && hasLoop(objectives)) problems.add("The prerequisites go in a circle: an objective can't be needed by something it needs.");
        return problems.stream().distinct().toList();
    }

    private static boolean hasLoop(List<ObjectiveSpec> objectives) {
        Map<String, List<String>> edges = new HashMap<>();
        for (ObjectiveSpec o : objectives) edges.put(o.key(), o.prerequisites());
        Set<String> done = new HashSet<>();
        for (String start : edges.keySet()) {
            if (loopFrom(start, edges, new HashSet<>(), done)) return true;
        }
        return false;
    }

    private static boolean loopFrom(String key, Map<String, List<String>> edges, Set<String> path, Set<String> done) {
        if (done.contains(key)) return false;
        if (!path.add(key)) return true;
        for (String next : edges.getOrDefault(key, List.of())) {
            if (loopFrom(next, edges, path, done)) return true;
        }
        path.remove(key);
        done.add(key);
        return false;
    }

    /** Whether progress from {@code source} may be recorded for an objective with this evidence mode. */
    public static boolean evidenceAllows(Evidence evidence, Source source) {
        if (source == Source.MANUAL) return true; // an organiser may always record by hand
        return switch (evidence) {
            case AUTO -> source == Source.AUTO || source == Source.IMPORT;
            case PROOF -> source == Source.CLAIM;
            case EITHER -> source == Source.AUTO || source == Source.CLAIM || source == Source.IMPORT;
            case ORGANISER -> false;
        };
    }
}
