package com.younglings.bot.commands.events;

import com.younglings.bot.events.TeamBuilder;
import com.younglings.bot.events.TeamBuilder.Player;
import com.younglings.bot.events.TeamBuilder.Team;
import com.younglings.bot.events.TeamRules.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsTeamCommandTest {
    private static Player player(long id, Long team, Integer rating) {
        return new Player(id, Kind.HOME_CLAN, team, rating, null);
    }

    @Test
    void theListShowsEachTeamThenWhoNeedsOneThenWhoCannotBeOnOneYet() {
        List<Team> teams = List.of(new Team(1, "Red", 501L), new Team(2, "Blue", null), new Team(3, "Green", null));
        List<Player> players = List.of(player(501, 1L, 4), player(502, 1L, 2), player(503, 2L, 3), player(504, null, null), player(505, null, null));
        Map<Long, String> blocked = new LinkedHashMap<>();
        blocked.put(505L, "not verified");
        List<String> lines = EventsTeamCommand.listLines("Friday bingo", teams, TeamBuilder.totals(teams, players), players, blocked, "3 teams, 3 of 4 players placed. 1 player still needs a team.");
        assertEquals("### Teams · Friday bingo", lines.get(0));
        assertEquals("**Red** · 2 players · rating 6 (average 3.0) · captain <@501>\n-# <@501> <@502>", lines.get(1));
        assertEquals("**Blue** · 1 player · rating 3 (average 3.0)\n-# <@503>", lines.get(2));
        assertEquals("**Green** · 0 players", lines.get(3));
        assertEquals("**No team** · 1\n-# <@504>", lines.get(4));
        assertEquals("**Can't be on a team yet** · 1\n-# <@505> (not verified)", lines.get(5));
        assertEquals("-# 3 teams, 3 of 4 players placed. 1 player still needs a team.", lines.get(6));
        assertEquals(7, lines.size());
    }

    @Test
    void anEventWithNoTeamsSaysHowToAddOneAndAHugeTeamIsCutShort() {
        List<String> none = EventsTeamCommand.listLines("Empty", List.of(), Map.of(), List.of(), Map.of(), "");
        assertEquals(List.of("### Teams · Empty", "No teams yet. Add one with `/events team create`."), none);

        List<Team> teams = List.of(new Team(1, "Everyone", null));
        List<Player> many = new ArrayList<>();
        for (int i = 1; i <= 45; i++) many.add(player(1000 + i, 1L, 3));
        String big = EventsTeamCommand.listLines("Big", teams, TeamBuilder.totals(teams, many), many, Map.of(), "").get(1);
        assertTrue(big.contains("<@1030>"), "the first thirty are named");
        assertTrue(!big.contains("<@1031>"), "the rest are not");
        assertTrue(big.endsWith("and 15 more"), big);
    }
}
