package com.younglings.bot.events;

import com.younglings.bot.events.TeamRules.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules behind building the teams of an event by hand: how strong a player is thought to be, how balanced the teams are, a starting suggestion an organiser
 * can accept or ignore, and what has to be true before the teams can be called final. Pure, so it can be tested.
 * <p>
 * Strength comes from two places. An organiser can rate a player from 1 to 5. Without a rating, a player whose RuneScape name is linked gets one worked out from
 * their total XP compared with the other players in the event ({@link #suggestedRatings}), and a player nobody knows anything about counts as average. The
 * rating an organiser gave always wins ({@link #effectiveRatings}).
 */
public final class TeamBuilder {
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    /** What a player counts as when there is nothing to go on. */
    public static final int UNKNOWN_RATING = 3;

    /** What is known of a player's account: the latest stats of their linked RuneScape name. */
    public record Strength(String rsn, int totalLevel, int combatLevel, long totalXp) {}

    /** One player as the builder sees them: who they are to the host, which team they are on (if any), what an organiser rated them, and what their account says. */
    public record Player(long userId, Kind kind, Long teamId, Integer rating, Strength strength) {}

    /** A team: its id, name and captain (a player of that team, or nobody). */
    public record Team(long id, String name, Long captainId) {}

    /** How one team adds up. {@code unrated} is players with neither a rating nor stats, so counted as average. */
    public record Totals(int members, int ratingTotal, double average, int unrated) {}

    /** One player placed on a team (or taken off, {@code teamId} null). */
    public record Move(long userId, Long teamId) {}

    /** A starting point for the teams: where each unplaced player would go, and what couldn't be placed and why. Nothing is changed until an organiser applies it. */
    public record Suggestion(List<Move> moves, List<String> notes) {
        public Suggestion {
            moves = List.copyOf(moves);
            notes = List.copyOf(notes);
        }
    }

    private TeamBuilder() {}

    public static boolean validRating(int rating) {
        return rating >= MIN_RATING && rating <= MAX_RATING;
    }

    // ---------- strength ----------

    /**
     * A rating for each player who has stats, spread from 1 (least total XP among them) to 5 (most), so the roster is compared with itself and not with some
     * fixed scale. Players with the same XP share a rating. With fewer than two players to compare nobody gets one, since there is nothing to compare with.
     */
    public static Map<Long, Integer> suggestedRatings(List<Player> players) {
        List<Player> known = players.stream().filter(p -> p.strength() != null).sorted(Comparator.comparingLong((Player p) -> p.strength().totalXp()).thenComparingLong(Player::userId)).toList();
        Map<Long, Integer> ratings = new HashMap<>();
        if (known.size() < 2) return ratings;
        int last = known.size() - 1;
        Map<Long, Integer> firstIndexOfXp = new HashMap<>();
        for (int i = 0; i < known.size(); i++) firstIndexOfXp.putIfAbsent(known.get(i).strength().totalXp(), i);
        for (Player p : known) {
            int index = firstIndexOfXp.get(p.strength().totalXp());
            ratings.put(p.userId(), MIN_RATING + (int) Math.round(index * (double) (MAX_RATING - MIN_RATING) / last));
        }
        return ratings;
    }

    /** What each player counts as: the rating an organiser gave, else the one worked out from their stats, else average. */
    public static Map<Long, Integer> effectiveRatings(List<Player> players) {
        Map<Long, Integer> suggested = suggestedRatings(players);
        Map<Long, Integer> effective = new LinkedHashMap<>();
        for (Player p : players) effective.put(p.userId(), p.rating() != null ? p.rating() : suggested.getOrDefault(p.userId(), UNKNOWN_RATING));
        return effective;
    }

    // ---------- how the teams add up ----------

    /** Each team's size and rating. A player with no team isn't counted anywhere. */
    public static Map<Long, Totals> totals(List<Team> teams, List<Player> players) {
        Map<Long, Integer> effective = effectiveRatings(players);
        Map<Long, Integer> suggested = suggestedRatings(players);
        Map<Long, Totals> result = new LinkedHashMap<>();
        for (Team team : teams) {
            int members = 0;
            int total = 0;
            int unrated = 0;
            for (Player p : players) {
                if (p.teamId() == null || p.teamId() != team.id()) continue;
                members++;
                total += effective.get(p.userId());
                if (p.rating() == null && !suggested.containsKey(p.userId())) unrated++;
            }
            result.put(team.id(), new Totals(members, total, members == 0 ? 0 : Math.round(total * 10.0 / members) / 10.0, unrated));
        }
        return result;
    }

    // ---------- a starting suggestion ----------

    /**
     * Places the players who have no team (and may be on one: {@code eligible}), strongest first, each on the team that is weakest so far (fewest rating points,
     * then fewest players), so the teams end up level. {@code players} is everyone who holds a place: players already on a team stay where they are and count
     * towards it, and ratings are worked out among all of them. A team a player may not join under the event's rules ({@link TeamRules}) is skipped; a player
     * who fits nowhere is left out with a note. Ties between players are broken by total XP and then by id so the answer is always the same.
     */
    public static Suggestion suggest(List<Team> teams, List<Player> players, java.util.Set<Long> eligible, TeamRules.Policy policy) {
        List<String> notes = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        List<Player> unplaced = players.stream().filter(p -> p.teamId() == null && eligible.contains(p.userId())).toList();
        if (unplaced.isEmpty()) return new Suggestion(moves, notes);
        if (teams.isEmpty()) {
            notes.add("Create the teams first.");
            return new Suggestion(moves, notes);
        }
        Map<Long, Integer> effective = effectiveRatings(players);
        Map<Long, Integer> total = new HashMap<>();
        Map<Long, Integer> size = new HashMap<>();
        Map<Long, List<Kind>> kinds = new HashMap<>();
        for (Team team : teams) {
            total.put(team.id(), 0);
            size.put(team.id(), 0);
            kinds.put(team.id(), new ArrayList<>());
        }
        for (Player p : players) {
            if (p.teamId() == null || !total.containsKey(p.teamId())) continue;
            total.merge(p.teamId(), effective.get(p.userId()), Integer::sum);
            size.merge(p.teamId(), 1, Integer::sum);
            kinds.get(p.teamId()).add(p.kind());
        }
        List<Player> order = new ArrayList<>(unplaced);
        order.sort(Comparator.comparingInt((Player p) -> -effective.get(p.userId()))
                .thenComparingLong(p -> p.strength() == null ? 0 : -p.strength().totalXp())
                .thenComparingLong(Player::userId));
        int skipped = 0;
        for (Player p : order) {
            Team best = null;
            for (Team team : teams) {
                if (!TeamRules.mayShareTeam(policy, p.kind(), kinds.get(team.id()))) continue;
                if (best == null || total.get(team.id()) < total.get(best.id())
                        || (total.get(team.id()).equals(total.get(best.id())) && size.get(team.id()) < size.get(best.id()))) {
                    best = team;
                }
            }
            if (best == null) {
                skipped++;
                continue;
            }
            moves.add(new Move(p.userId(), best.id()));
            total.merge(best.id(), effective.get(p.userId()), Integer::sum);
            size.merge(best.id(), 1, Integer::sum);
            kinds.get(best.id()).add(p.kind());
        }
        if (skipped > 0) notes.add(skipped + (skipped == 1 ? " player doesn't" : " players don't") + " fit on any team under this event's rules about who may share a team, so they were left out.");
        return new Suggestion(moves, notes);
    }

    // ---------- when the teams can be called final ----------

    /**
     * What the teams look like when asked whether they can be final: how many teams there are, how many players should be on one and how many still aren't, which
     * teams are empty, and how many players on a team haven't passed verification (so shouldn't be there).
     */
    public record Facts(int teams, int pool, int unassigned, List<String> emptyTeams, int excludedOnTeams) {
        public Facts {
            emptyTeams = List.copyOf(emptyTeams);
        }
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** What is still wrong with the teams, in words an organiser can act on. Empty when they could be made final. */
    public static List<String> problems(Facts f) {
        List<String> problems = new ArrayList<>();
        if (f.teams() == 0) {
            problems.add("Create the teams.");
            return problems;
        }
        if (f.unassigned() > 0) problems.add(plural(f.unassigned(), "player still needs", "players still need") + " a team.");
        if (!f.emptyTeams().isEmpty()) {
            problems.add(f.emptyTeams().size() == 1 ? "Team " + f.emptyTeams().getFirst() + " has no players: add some or remove it."
                    : plural(f.emptyTeams().size(), "team has", "teams have") + " no players (" + String.join(", ", f.emptyTeams()) + "): add some or remove them.");
        }
        if (f.excludedOnTeams() > 0) problems.add(plural(f.excludedOnTeams(), "player on a team hasn't", "players on teams haven't") + " passed verification: verify, waive or take them off their team.");
        return problems;
    }
}
