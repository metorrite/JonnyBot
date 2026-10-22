package com.younglings.bot.events;

import com.younglings.bot.events.ClanVsClan.Report;
import com.younglings.bot.events.ClanVsClan.Scoring;
import com.younglings.bot.events.ClanVsClan.Settings;
import com.younglings.bot.events.ClanVsClan.TeamChoice;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Entry;
import com.younglings.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clan-vs-clan rules (T-222): who is on which clan's team, and the three ways clans are scored. */
class ClanVsClanTest {
    private static final long HOST = 1, ALLY = 2, OTHER = 3;
    private static final long TEAM_A = 10, TEAM_B = 20;
    private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");

    @Test
    void aPlayerOnOneParticipatingRosterGoesOnThatClansTeam() {
        Map<Long, Long> teams = Map.of(HOST, TEAM_A, ALLY, TEAM_B);
        assertEquals(new TeamChoice.One(TEAM_B), ClanVsClan.teamFor(Set.of(ALLY), HOST, teams));
        assertEquals(new TeamChoice.One(TEAM_B), ClanVsClan.teamFor(Set.of(ALLY, OTHER), HOST, teams), "a clan that isn't taking part doesn't count");
    }

    @Test
    void aPlayerOnNoParticipatingRosterCantJoin() {
        assertInstanceOf(TeamChoice.NoClan.class, ClanVsClan.teamFor(Set.of(), HOST, Map.of(HOST, TEAM_A, ALLY, TEAM_B)));
        assertInstanceOf(TeamChoice.NoClan.class, ClanVsClan.teamFor(Set.of(OTHER), HOST, Map.of(HOST, TEAM_A, ALLY, TEAM_B)));
    }

    @Test
    void onTwoRostersTheClanTheyCameThroughWinsOtherwiseStaffChoose() {
        Map<Long, Long> teams = Map.of(HOST, TEAM_A, ALLY, TEAM_B);
        assertEquals(new TeamChoice.One(TEAM_B), ClanVsClan.teamFor(Set.of(HOST, ALLY), ALLY, teams));
        assertInstanceOf(TeamChoice.StaffChoose.class, ClanVsClan.teamFor(Set.of(HOST, ALLY), OTHER, teams));
    }

    /** A bingo-like board: three tiles of 10 points, each done once, and a bonus of 25 for the first two. */
    private static List<ObjectiveSpec> tiles() {
        return List.of(new ObjectiveSpec("a", "A", "", 10, Evidence.PROOF, 1, 1, List.of()), new ObjectiveSpec("b", "B", "", 10, Evidence.PROOF, 1, 1, List.of()),
                new ObjectiveSpec("c", "C", "", 10, Evidence.PROOF, 1, 1, List.of()));
    }

    private static List<EventBonuses.Bonus> bonus() {
        return List.of(new EventBonuses.Bonus("Row", List.of("a", "b"), 25));
    }

    /**
     * Clan A: three players each finishing one tile (30) plus the row bonus. Clan B (bigger): player 21 does all three tiles alone (30) and player 22 logs
     * tile a again, which counts for nobody (the tile was done).
     */
    private static List<Report> log() {
        return List.of(new Report(11, TEAM_A, "a", 1, T0.plusSeconds(10)), new Report(12, TEAM_A, "b", 1, T0.plusSeconds(20)), new Report(13, TEAM_A, "c", 1, T0.plusSeconds(30)),
                new Report(21, TEAM_B, "a", 1, T0.plusSeconds(5)), new Report(21, TEAM_B, "b", 1, T0.plusSeconds(6)), new Report(21, TEAM_B, "c", 1, T0.plusSeconds(7)),
                new Report(22, TEAM_B, "a", 1, T0.plusSeconds(8)));
    }

    private static List<Standing> teamStandings() {
        List<Entry> entries = new ArrayList<>();
        for (Report r : log()) entries.add(new Entry("t:" + r.teamId(), r.objectiveKey(), r.amount(), r.at()));
        return EventScoring.standings(tiles(), entries, bonus());
    }

    @Test
    void contributionsCreditThePlayerWhoseReportCompletedEachTileAndNothingTwice() {
        var earned = ClanVsClan.contributions(tiles(), log());
        assertEquals(Map.of(11L, 10L, 12L, 10L, 13L, 10L), earned.get(TEAM_A));
        assertEquals(Map.of(21L, 30L), earned.get(TEAM_B), "player 22 logged a tile that was already done");
    }

    @Test
    void totalKeepsThePlainTeamStandingsWithTheBonusOnce() {
        List<Standing> s = ClanVsClan.standings(new Settings("bingo", Scoring.TOTAL, 10, 3, false), teamStandings(), tiles(), ClanVsClan.contributions(tiles(), log()));
        // both clans: 30 for the tiles + 25 for the row; clan B finished earlier, so it wins the tie
        assertEquals(List.of("t:20", "t:10"), s.stream().map(Standing::subject).toList());
        assertEquals(55, s.get(0).points());
        assertEquals(55, s.get(1).points());
    }

    @Test
    void topNCountsOnlyTheBestPlayersPlusTheBonusOnce() {
        Settings top2 = new Settings("bingo", Scoring.TOP_N, 2, 3, false);
        List<Standing> s = ClanVsClan.standings(top2, teamStandings(), tiles(), ClanVsClan.contributions(tiles(), log()));
        // clan B: its best 2 are 30 and nothing = 30, + 25 bonus = 55; clan A: best 2 of 10/10/10 = 20, + 25 = 45
        assertEquals(List.of("t:20", "t:10"), s.stream().map(Standing::subject).toList());
        assertEquals(55, s.get(0).points());
        assertEquals(45, s.get(1).points());
    }

    @Test
    void averageDividesByThePlayersWhoScored() {
        Settings average = new Settings("bingo", Scoring.AVERAGE, 10, 3, false);
        List<Standing> s = ClanVsClan.standings(average, teamStandings(), tiles(), ClanVsClan.contributions(tiles(), log()));
        // clan B: 30 / 1 scorer + 25 = 55; clan A: 30 / 3 = 10, + 25 = 35
        assertEquals(55, s.stream().filter(x -> x.subject().equals("t:20")).findFirst().orElseThrow().points());
        assertEquals(35, s.stream().filter(x -> x.subject().equals("t:10")).findFirst().orElseThrow().points());
    }

    @Test
    void aCompetitionCreditsEachThresholdToThePlayerWhoseGainCrossedIt() {
        // one point per 100 XP, repeating
        List<ObjectiveSpec> xp = List.of(new ObjectiveSpec("xp-gained", "XP", "", 1, Evidence.AUTO, 100, 1_000_000, List.of()));
        List<Report> gains = List.of(new Report(11, TEAM_A, "xp-gained", 250, T0), new Report(12, TEAM_A, "xp-gained", 60, T0.plusSeconds(1)),
                new Report(21, TEAM_B, "xp-gained", 90, T0));
        var earned = ClanVsClan.contributions(xp, gains);
        assertEquals(Map.of(11L, 2L, 12L, 1L), earned.get(TEAM_A), "250 crosses 100 and 200; the next 60 crosses 300");
        assertTrue(earned.get(TEAM_B) == null || earned.get(TEAM_B).isEmpty());
    }

    @Test
    void theSetupAsksForTheInnerGameAndShowsEachGamesOwnOptionsOnlyForIt() {
        ClanVsClanTemplate template = new ClanVsClanTemplate(new BingoTemplate(), new CompetitionTemplate(), new BossNightTemplate(), new ScavengerHuntTemplate(), null, null, null, null, null);
        var options = template.options();
        List<String> paths = options.stream().map(EventOptions.Option::path).toList();
        assertTrue(paths.containsAll(List.of("config.cvcGame", "config.cvc.scoring", "config.cvc.topN", "config.cvc.minPlayers", "config.cvc.reviewByOtherClan", "config.boss", "config.skillId")));
        assertEquals(paths.size(), new java.util.HashSet<>(paths).size(), "each setting is asked once");
        var boss = options.stream().filter(o -> o.path().equals("config.boss")).findFirst().orElseThrow();
        assertTrue(!boss.required().always() && boss.required().when() != null, "the boss is only required when the clans play a boss night");
        assertTrue(boss.showWhen().holds(DataObject.fromJson("{\"config\":{\"cvcGame\":\"boss-night\"}}")));
        assertTrue(!boss.showWhen().holds(DataObject.fromJson("{\"config\":{\"cvcGame\":\"bingo\"}}")));
        assertEquals(EventRules.ScoringMode.TEAM, template.fixedScoringMode());
        assertTrue(template.configProblems(DataObject.fromJson("{\"cvcGame\":\"boss-night\",\"kills\":0}")).stream().anyMatch(p -> p.contains("Kills that count")), "the inner game's own checks run");
        assertTrue(template.configProblems(DataObject.fromJson("{\"cvcGame\":\"competition\",\"kills\":0}")).isEmpty(), "another game's settings aren't checked");
        // the whole schema the website draws the setup from builds
        assertTrue(EventOptions.of(template).size() > options.size());
    }

    @Test
    void settingsHaveSafeDefaultsAndTheGameDecidesWhatTheEventPlays() {
        assertEquals(Settings.DEFAULT, ClanVsClan.settings(DataObject.empty()));
        assertEquals("competition", ClanVsClan.game(ClanVsClan.KEY, "{\"cvcGame\":\"competition\"}"));
        assertEquals("bingo", ClanVsClan.game(ClanVsClan.KEY, "{\"cvcGame\":\"chess\"}"));
        assertEquals("boss-night", ClanVsClan.game("boss-night", "{\"cvcGame\":\"competition\"}"), "only a clan-vs-clan event has an inner game");
        assertTrue(ClanVsClan.problems(DataObject.fromJson("{\"cvc\":{\"scoring\":\"LOUDEST\"}}")).size() == 1);
        assertTrue(ClanVsClan.problems(DataObject.fromJson("{\"cvc\":{\"topN\":0}}")).size() == 1);
        assertEquals(Map.of(1L, 10L, 2L, 20L), ClanVsClan.teams(DataObject.fromJson("{\"cvcTeams\":{\"1\":10,\"2\":20}}")));
    }
}
