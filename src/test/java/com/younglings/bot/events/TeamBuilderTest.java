package com.younglings.bot.events;

import com.younglings.bot.events.TeamBuilder.Facts;
import com.younglings.bot.events.TeamBuilder.Move;
import com.younglings.bot.events.TeamBuilder.Player;
import com.younglings.bot.events.TeamBuilder.Strength;
import com.younglings.bot.events.TeamBuilder.Suggestion;
import com.younglings.bot.events.TeamBuilder.Team;
import com.younglings.bot.events.TeamBuilder.Totals;
import com.younglings.bot.events.TeamRules.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamBuilderTest {
    private static Player player(long id, Kind kind, Long team, Integer rating, Long xp) {
        return new Player(id, kind, team, rating, xp == null ? null : new Strength("rsn" + id, 2000, 138, xp));
    }

    private static Player player(long id, Long team, Integer rating, Long xp) {
        return player(id, Kind.HOME_CLAN, team, rating, xp);
    }

    private static Team team(long id, String name) {
        return new Team(id, name, null);
    }

    /** Applies a suggestion to a roster, the way an organiser accepting it would. */
    private static List<Player> applied(List<Player> players, Suggestion suggestion) {
        Map<Long, Long> teamOf = new HashMap<>();
        for (Move m : suggestion.moves()) teamOf.put(m.userId(), m.teamId());
        List<Player> after = new ArrayList<>();
        for (Player p : players) after.add(teamOf.containsKey(p.userId()) ? new Player(p.userId(), p.kind(), teamOf.get(p.userId()), p.rating(), p.strength()) : p);
        return after;
    }

    // ---------- strength ----------

    @Test
    void ratingsAreSpreadFromOneToFiveByTotalXpAmongThePlayersWhoHaveStats() {
        List<Player> players = List.of(player(1, null, null, 500L), player(2, null, null, 100L), player(3, null, null, 300L), player(4, null, null, 200L), player(5, null, null, 400L));
        Map<Long, Integer> ratings = TeamBuilder.suggestedRatings(players);
        assertEquals(Map.of(2L, 1, 4L, 2, 3L, 3, 5L, 4, 1L, 5), ratings);
        // three players: the weakest, the middle and the strongest
        assertEquals(Map.of(2L, 1, 3L, 3, 1L, 5), TeamBuilder.suggestedRatings(List.of(player(1, null, null, 9L), player(2, null, null, 1L), player(3, null, null, 5L))));
    }

    @Test
    void playersWithTheSameXpShareARatingAndOnePlayerAloneGetsNone() {
        Map<Long, Integer> ratings = TeamBuilder.suggestedRatings(List.of(player(1, null, null, 100L), player(2, null, null, 200L), player(3, null, null, 200L), player(4, null, null, 300L), player(5, null, null, 400L)));
        assertEquals(ratings.get(2L), ratings.get(3L));
        assertEquals(1, ratings.get(1L));
        assertEquals(5, ratings.get(5L));
        assertTrue(TeamBuilder.suggestedRatings(List.of(player(1, null, null, 100L), player(2, null, null, null))).isEmpty(), "one player with stats has nobody to be compared with");
        assertTrue(TeamBuilder.suggestedRatings(List.of()).isEmpty());
    }

    @Test
    void anOrganisersRatingBeatsTheSuggestedOneAndPlayersNobodyKnowsCountAsAverage() {
        List<Player> players = List.of(player(1, null, 5, 100L), player(2, null, null, 900L), player(3, null, null, null), player(4, null, null, 400L));
        Map<Long, Integer> effective = TeamBuilder.effectiveRatings(players);
        assertEquals(5, effective.get(1L), "the rating an organiser gave wins over low XP");
        assertEquals(5, effective.get(2L), "the most XP of the three with stats");
        assertEquals(TeamBuilder.UNKNOWN_RATING, effective.get(3L), "no rating and no stats");
        assertEquals(3, effective.get(4L), "the middle of the three with stats");
        assertTrue(TeamBuilder.validRating(1) && TeamBuilder.validRating(5));
        assertFalse(TeamBuilder.validRating(0) || TeamBuilder.validRating(6));
    }

    // ---------- how the teams add up ----------

    @Test
    void eachTeamAddsUpItsPlayersAndAnEmptyTeamIsZero() {
        List<Team> teams = List.of(team(1, "Red"), team(2, "Blue"), team(3, "Green"));
        List<Player> players = List.of(player(1, 1L, 4, null), player(2, 1L, 2, null), player(3, 2L, null, null), player(4, null, 5, null));
        Map<Long, Totals> totals = TeamBuilder.totals(teams, players);
        assertEquals(new Totals(2, 6, 3.0, 0), totals.get(1L));
        assertEquals(new Totals(1, 3, 3.0, 1), totals.get(2L), "an unrated, unknown player counts as average and is counted as unrated");
        assertEquals(new Totals(0, 0, 0, 0), totals.get(3L));
        assertEquals(List.of(1L, 2L, 3L), List.copyOf(totals.keySet()), "in the order of the teams");
    }

    // ---------- the suggestion ----------

    @Test
    void aSuggestionLevelsTheTeamsStrongestFirst() {
        List<Team> teams = List.of(team(1, "Red"), team(2, "Blue"));
        List<Player> players = new ArrayList<>();
        int[] ratings = {5, 5, 4, 4, 3, 3, 2, 2, 1, 1};
        for (int i = 0; i < ratings.length; i++) players.add(player(i + 1, null, ratings[i], null));
        Suggestion suggestion = TeamBuilder.suggest(teams, players, Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), TeamRules.everyoneTogether());
        assertEquals(10, suggestion.moves().size());
        assertTrue(suggestion.notes().isEmpty());
        Map<Long, Totals> totals = TeamBuilder.totals(teams, applied(players, suggestion));
        assertEquals(5, totals.get(1L).members());
        assertEquals(5, totals.get(2L).members());
        assertEquals(15, totals.get(1L).ratingTotal());
        assertEquals(15, totals.get(2L).ratingTotal());
        assertEquals(suggestion, TeamBuilder.suggest(teams, players, Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), TeamRules.everyoneTogether()), "the same roster always gives the same answer");
    }

    @Test
    void aSuggestionKeepsPlayersWhoAreAlreadyPlacedAndBalancesAgainstThem() {
        List<Team> teams = List.of(team(1, "Red"), team(2, "Blue"));
        List<Player> players = List.of(player(1, 1L, 5, null), player(2, 1L, 5, null), player(3, null, 4, null), player(4, null, 3, null), player(5, null, 2, null), player(6, null, 1, null), player(7, null, 1, null));
        Suggestion suggestion = TeamBuilder.suggest(teams, players, Set.of(3L, 4L, 5L, 6L, 7L), TeamRules.everyoneTogether());
        assertEquals(5, suggestion.moves().size(), "only the players without a team are placed");
        assertTrue(suggestion.moves().stream().noneMatch(m -> m.userId() == 1 || m.userId() == 2));
        Map<Long, Totals> totals = TeamBuilder.totals(teams, applied(players, suggestion));
        assertEquals(11, totals.get(1L).ratingTotal(), "Red already had two fives");
        assertEquals(10, totals.get(2L).ratingTotal());
    }

    @Test
    void aSuggestionOnlyPlacesPlayersWhoMayBeOnATeam() {
        List<Team> teams = List.of(team(1, "Red"));
        List<Player> players = List.of(player(1, null, 3, null), player(2, null, 3, null));
        Suggestion suggestion = TeamBuilder.suggest(teams, players, Set.of(2L), TeamRules.everyoneTogether());
        assertEquals(List.of(new Move(2, 1L)), suggestion.moves(), "player 1 hasn't passed verification, so isn't eligible");
    }

    @Test
    void aSuggestionRespectsWhoMayShareATeamAndSaysWhoFitsNowhere() {
        List<Team> teams = List.of(team(1, "Clan"), team(2, "Others"));
        List<Player> players = List.of(
                player(1, Kind.HOME_CLAN, 1L, 3, null), player(2, Kind.HOME_OTHER, 2L, 3, null),
                player(3, Kind.HOME_CLAN, null, 5, null), player(4, Kind.PARTNER, null, 4, null));
        Suggestion suggestion = TeamBuilder.suggest(teams, players, Set.of(3L, 4L), TeamRules.clanVersusOutsiders());
        assertEquals(List.of(new Move(3, 1L), new Move(4, 2L)), suggestion.moves(), "clan members only with clan members, everyone else together");
        assertTrue(suggestion.notes().isEmpty());

        Suggestion nowhere = TeamBuilder.suggest(List.of(team(2, "Others")), List.of(player(2, Kind.HOME_OTHER, 2L, 3, null), player(3, Kind.HOME_CLAN, null, 5, null)), Set.of(3L), TeamRules.clanVersusOutsiders());
        assertTrue(nowhere.moves().isEmpty());
        assertEquals(List.of("1 player doesn't fit on any team under this event's rules about who may share a team, so they were left out."), nowhere.notes());
    }

    @Test
    void aSuggestionNeedsTeamsAndHasNothingToDoWhenEveryoneIsPlaced() {
        assertEquals(List.of("Create the teams first."), TeamBuilder.suggest(List.of(), List.of(player(1, null, 3, null)), Set.of(1L), TeamRules.everyoneTogether()).notes());
        Suggestion done = TeamBuilder.suggest(List.of(team(1, "Red")), List.of(player(1, 1L, 3, null)), Set.of(1L), TeamRules.everyoneTogether());
        assertTrue(done.moves().isEmpty());
        assertTrue(done.notes().isEmpty());
    }

    // ---------- when the teams can be final ----------

    @Test
    void whatIsWrongWithTheTeamsIsSaidInWords() {
        assertEquals(List.of("Create the teams."), TeamBuilder.problems(new Facts(0, 6, 6, List.of(), 0)));
        assertTrue(TeamBuilder.problems(new Facts(2, 6, 0, List.of(), 0)).isEmpty());
        assertEquals(List.of("1 player still needs a team."), TeamBuilder.problems(new Facts(2, 6, 1, List.of(), 0)));
        assertEquals(List.of("3 players still need a team."), TeamBuilder.problems(new Facts(2, 6, 3, List.of(), 0)));
        assertEquals(List.of("Team Red has no players: add some or remove it."), TeamBuilder.problems(new Facts(2, 6, 0, List.of("Red"), 0)));
        assertEquals(List.of("2 teams have no players (Red, Blue): add some or remove them."), TeamBuilder.problems(new Facts(3, 6, 0, List.of("Red", "Blue"), 0)));
        assertEquals(List.of("1 player on a team hasn't passed verification: verify, waive or take them off their team."), TeamBuilder.problems(new Facts(2, 6, 0, List.of(), 1)));
        assertEquals(3, TeamBuilder.problems(new Facts(2, 6, 2, List.of("Red"), 4)).size(), "everything wrong is listed");
    }
}
