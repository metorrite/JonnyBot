package com.runeclans.bot.leagues;

import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRoster;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.events.PrizeRules.BuyInStatus;
import com.runeclans.bot.events.TeamRules.Kind;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of an event round (T-759): finding the attached round, each clan's result, and the report the hub takes. */
class LeagueRoundsTest {
    // fake ids, made at runtime
    private static final long HOST = Long.parseLong("2".repeat(18));
    private static final long GUEST = Long.parseLong("3".repeat(18));
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";

    private static DataObject round(int number, String kind, Long eventId, String status, boolean host, String metric) {
        DataObject r = DataObject.empty().put("number", number).put("kind", kind).put("status", status).put("isHost", host).put("needsBot", host && !status.equals("final"))
                .put("config", DataObject.empty().put("metric", metric)).put("resultSource", null);
        if (eventId != null) r.put("eventRef", DataObject.empty().put("eventId", Long.toString(eventId)).put("guildId", Long.toString(HOST)));
        return r;
    }

    private static DataObject guildLeagues(DataObject... rounds) {
        DataArray arr = DataArray.empty();
        for (DataObject r : rounds) arr.add(r);
        return DataObject.empty().put("guildId", Long.toString(HOST)).put("clanId", "c1").put("leagues",
                DataArray.empty().add(DataObject.empty().put("id", LEAGUE).put("name", "Winter cup").put("rounds", arr)));
    }

    private static Participant player(long userId, Long teamId, boolean waiting) {
        return new Participant(1, userId, HOST, teamId, Kind.HOME_CLAN, waiting, BuyInStatus.NONE, Instant.EPOCH, EventRoster.Verification.NOT_REQUIRED, null, null, null, null, null);
    }

    @Test
    void theAttachedRoundIsFoundByItsEventIdAndNothingElse() {
        DataObject json = guildLeagues(round(1, "event", 77L, "final", true, "placing"), round(2, "event", 78L, "running", true, "participation"), round(3, "manual", null, "planned", false, ""));
        Optional<LeagueRounds.Attached> a = LeagueRounds.attached(json, 78);
        assertTrue(a.isPresent());
        assertEquals(LEAGUE, a.get().leagueId());
        assertEquals("Winter cup", a.get().leagueName());
        assertEquals(2, a.get().number());
        assertEquals(LeagueRounds.METRIC_PARTICIPATION, a.get().metric());
        assertTrue(a.get().isHost());
        assertFalse(a.get().over());
        assertTrue(LeagueRounds.attached(json, 77).get().over(), "a final round takes no result");
        assertTrue(LeagueRounds.attached(json, 79).isEmpty(), "attached nowhere");
        assertTrue(LeagueRounds.attached(null, 78).isEmpty());
        assertTrue(LeagueRounds.attached(DataObject.empty(), 78).isEmpty());
    }

    @Test
    void theMetricIsPlacingUnlessTheRoundSaysParticipation() {
        assertEquals("placing", LeagueRounds.metricOf(DataObject.empty()));
        assertEquals("placing", LeagueRounds.metricOf(null));
        assertEquals("participation", LeagueRounds.metricOf(DataObject.empty().put("metric", " Participation ")));
        assertEquals("placing", LeagueRounds.metricOf(DataObject.empty().put("metric", "points")));
    }

    @Test
    void eachClanGetsItsTeamsPointsOrItsPlayerCount() {
        List<Standing> standings = List.of(new Standing("t:10", 340, 3, Map.of(), null), new Standing("t:20", 120, 1, Map.of(), null), new Standing("u:5", 999, 9, Map.of(), null));
        Map<Long, Long> teams = Map.of(HOST, 10L, GUEST, 20L);
        List<Participant> roster = List.of(player(1, 10L, false), player(2, 10L, false), player(3, 10L, true), player(4, 20L, false), player(5, null, false));
        List<LeagueRounds.ClanResult> placing = LeagueRounds.results("placing", standings, teams, roster);
        assertEquals(List.of(new LeagueRounds.ClanResult(HOST, 340, 2), new LeagueRounds.ClanResult(GUEST, 120, 1)), placing, "a waiting player and a player on no team don't count");
        List<LeagueRounds.ClanResult> participation = LeagueRounds.results("participation", standings, teams, roster);
        assertEquals(List.of(new LeagueRounds.ClanResult(HOST, 2, 2), new LeagueRounds.ClanResult(GUEST, 1, 1)), participation);
        List<LeagueRounds.ClanResult> noScore = LeagueRounds.results("placing", List.of(), teams, List.of());
        assertEquals(List.of(new LeagueRounds.ClanResult(HOST, 0, 0), new LeagueRounds.ClanResult(GUEST, 0, 0)), noScore, "a clan that scored nothing is still in, with 0");
    }

    @Test
    void theReportNamesEveryClanByItsHubIdOrIsNotMade() {
        List<LeagueRounds.ClanResult> results = List.of(new LeagueRounds.ClanResult(HOST, 340, 2), new LeagueRounds.ClanResult(GUEST, 120, 1));
        Map<Long, String> ids = Map.of(HOST, "c1", GUEST, "c2");
        Optional<DataArray> clans = LeagueRounds.clansJson(results, g -> Optional.ofNullable(ids.get(g)));
        assertTrue(clans.isPresent());
        assertEquals(2, clans.get().length());
        assertEquals("c2", clans.get().getObject(1).getString("clanId"));
        assertEquals(120, clans.get().getObject(1).getInt("raw"));
        assertEquals(1, clans.get().getObject(1).getInt("participants"));
        assertTrue(LeagueRounds.clansJson(results, g -> g == HOST ? Optional.of("c1") : Optional.empty()).isEmpty(), "a clan without a hub clan: entered by hand instead");
    }
}
