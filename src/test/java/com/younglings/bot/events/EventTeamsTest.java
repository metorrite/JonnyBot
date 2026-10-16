package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRoster.Verification;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.TeamRules.Kind;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building an event's teams over a real {@link EventService} and {@link EventRepository} on the developer's local Postgres (skipped without one): who may be put
 * on a team, captains, moving many players at once, calling the teams final, ratings, and what the team builder knows about each player. Everything is made in a
 * made-up server and removed afterwards.
 */
class EventTeamsTest {
    private static final long G = 999_000_000_000_000_051L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private PlayerLinkRepository links;
    private EventTeams teams;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        links = Mockito.mock(PlayerLinkRepository.class);
        teams = new EventTeams(service, repository, links);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    // ---------- helpers ----------

    private long event(String name, ScoringMode mode, String config, int max) {
        long id = service.createEvent(G, 9L, new EventService.EventInput(name, "", "custom", Scope.PRIVATE, mode, null, null, max, DataObject.fromJson(config)));
        service.setObjectives(G, id, List.of(new ObjectiveSpec("kill", "Kill it", "", 10, Evidence.EITHER, 1, 1, List.of())));
        service.moveStage(G, id, Status.SIGNUPS);
        return id;
    }

    private long teamEvent(String name, String config) {
        return event(name, ScoringMode.TEAM, config, 0);
    }

    private void join(long id, long... users) {
        for (long user : users) service.join(G, id, G, user, null, true, null);
    }

    private Participant player(long id, long userId) {
        return repository.participant(id, userId).orElseThrow();
    }

    private EventRepository.Team team(long id, long teamId) {
        return repository.teams(id).stream().filter(t -> t.id() == teamId).findFirst().orElseThrow();
    }

    private static EventProblem refused(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(EventProblem.class, action);
    }

    // ---------- who may be put on a team ----------

    @Test
    void aPlayerOnTheWaitlistCannotBePutOnATeam() {
        long id = event("Small", ScoringMode.TEAM, "{}", 1);
        join(id, 501, 502);
        long red = service.addTeam(G, id, "Red", null);
        EventProblem waiting = refused(() -> service.setTeam(G, id, 502, red));
        assertEquals(409, waiting.status());
        assertTrue(waiting.getMessage().contains("waitlist"), waiting.getMessage());
        service.setTeam(G, id, 501, red);
        assertEquals(red, player(id, 501).teamId());
    }

    @Test
    void aPlayerWhoHasNotPassedTheVerificationTheEventNeedsBeforeTeamsCannotBePutOnOneUntilWaived() {
        long id = teamEvent("Verify first", "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\"}}");
        join(id, 501);
        long red = service.addTeam(G, id, "Red", null);
        EventProblem pending = refused(() -> service.setTeam(G, id, 501, red));
        assertEquals(409, pending.status());
        assertTrue(pending.getMessage().contains("hasn't been verified"), pending.getMessage());
        assertNull(player(id, 501).teamId());

        service.setVerification(G, id, 501, Verification.WAIVED, 9L, "");
        service.setTeam(G, id, 501, red);
        assertEquals(red, player(id, 501).teamId(), "an organiser letting them through is the override");

        // turned down later: they stay where they are but the checklist says so, and they can always be taken off a team
        service.setVerification(G, id, 501, Verification.REJECTED, 9L, "blurry");
        var teamsStep = service.prepOf(service.event(G, id)).steps().stream().filter(s -> s.key().equals("teams")).findFirst().orElseThrow();
        assertTrue(teamsStep.detail().contains("hasn't passed verification"), teamsStep.detail());
        service.setTeam(G, id, 501, null);
        assertNull(player(id, 501).teamId());
        assertTrue(refused(() -> service.setTeam(G, id, 501, red)).getMessage().contains("turned down"));
    }

    @Test
    void aPendingPlayerCanBePutOnATeamWhenVerificationIsOnlyNeededBeforeTheStart() {
        long id = teamEvent("Verify later", "{\"verification\":{\"required\":true,\"gate\":\"START\"}}");
        join(id, 501);
        long red = service.addTeam(G, id, "Red", null);
        service.setTeam(G, id, 501, red);
        assertEquals(red, player(id, 501).teamId());
    }

    // ---------- teams and captains ----------

    @Test
    void aTeamCanBeRenamedWhileTheEventIsBeingBuiltAndNamesStayUnique() {
        long id = teamEvent("Names", "{}");
        long red = service.addTeam(G, id, "Red", null);
        service.addTeam(G, id, "Blue", null);
        service.renameTeam(G, id, red, "Crimson");
        assertEquals("Crimson", team(id, red).name());
        assertEquals(409, refused(() -> service.renameTeam(G, id, red, "blue")).status(), "names are unique whatever the case");
        assertEquals(400, refused(() -> service.renameTeam(G, id, red, "  ")).status());
        assertEquals(404, refused(() -> service.renameTeam(G, id, 123456789L, "Nope")).status());
        service.moveStage(G, id, Status.RUNNING);
        assertEquals(409, refused(() -> service.renameTeam(G, id, red, "Scarlet")).status(), "teams are named before the event starts");
    }

    @Test
    void aCaptainHasToBeOnTheTeamAndStopsBeingCaptainWhenMovedOrGone() {
        long id = teamEvent("Captains", "{}");
        join(id, 501, 502, 503);
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);
        service.setTeam(G, id, 501, red);
        service.setTeam(G, id, 502, red);

        service.setCaptain(G, id, red, 501L);
        assertEquals(501L, team(id, red).captainId());
        assertEquals(409, refused(() -> service.setCaptain(G, id, blue, 501L)).status(), "501 isn't on Blue");
        assertEquals(409, refused(() -> service.setCaptain(G, id, red, 503L)).status(), "503 isn't on any team");
        assertEquals(404, refused(() -> service.setCaptain(G, id, 123456789L, 501L)).status());

        service.setTeam(G, id, 501, blue);
        assertNull(team(id, red).captainId(), "moved off the team, so no longer its captain");
        service.setCaptain(G, id, red, 502L);
        service.leave(G, id, 502);
        assertNull(team(id, red).captainId(), "left the event");
        service.setTeam(G, id, 503, red);
        service.setCaptain(G, id, red, 503L);
        service.setCaptain(G, id, red, null);
        assertNull(team(id, red).captainId());
    }

    @Test
    void aTeamAddedWithACaptainPutsThemOnItAndAFailureLeavesNoTeamBehind() {
        long id = teamEvent("Captain at once", "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\"}}");
        join(id, 501, 502);
        service.setVerification(G, id, 501, Verification.VERIFIED, 9L, "");
        long green = service.addTeam(G, id, "Green", 501L);
        assertEquals(green, player(id, 501).teamId());
        assertEquals(501L, team(id, green).captainId());

        assertEquals(409, refused(() -> service.addTeam(G, id, "Unverified", 502L)).status(), "502 hasn't passed verification");
        assertTrue(repository.teams(id).stream().noneMatch(t -> t.name().equals("Unverified")), "the failed team was taken back");
        assertEquals(404, refused(() -> service.addTeam(G, id, "Nobody", 999L)).status());
        assertTrue(repository.teams(id).stream().noneMatch(t -> t.name().equals("Nobody")));
    }

    // ---------- moving many players at once ----------

    @Test
    void manyMovesAreMadeAllOrNothing() {
        long id = teamEvent("Bulk", "{}");
        join(id, 501, 502, 503);
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);

        Map<Long, Long> bad = new LinkedHashMap<>();
        bad.put(501L, red);
        bad.put(502L, blue);
        bad.put(503L, 424242L);
        EventProblem refusal = refused(() -> service.assignTeams(G, id, bad, u -> "Player " + u));
        assertEquals(400, refusal.status());
        assertFalse(refusal.problems().isEmpty());
        assertTrue(repository.participants(id).stream().allMatch(p -> p.teamId() == null), "nothing moved because one move was wrong");

        Map<Long, Long> unknown = Map.of(777L, red);
        assertTrue(refused(() -> service.assignTeams(G, id, unknown, u -> "Player " + u)).problems().get(0).contains("isn't in the event"));

        Map<Long, Long> good = new LinkedHashMap<>();
        good.put(501L, red);
        good.put(502L, red);
        good.put(503L, blue);
        assertEquals(3, service.assignTeams(G, id, good, u -> "Player " + u));
        assertEquals(red, player(id, 502).teamId());
        assertEquals(blue, player(id, 503).teamId());
        Map<Long, Long> off = new LinkedHashMap<>();
        off.put(501L, null);
        assertEquals(1, service.assignTeams(G, id, off, u -> "Player " + u));
        assertNull(player(id, 501).teamId());
        assertEquals(0, service.assignTeams(G, id, Map.of(), u -> "Player " + u));
    }

    @Test
    void manyMovesAreCheckedAgainstTheTeamsAsTheyWillBe() {
        long id = teamEvent("Policy", "{\"teamPolicy\":\"clanVsOutsiders\"}");
        service.join(G, id, G, 501, null, true, Kind.HOME_CLAN);
        service.join(G, id, G, 502, null, true, Kind.HOME_OTHER);
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);

        Map<Long, Long> together = new LinkedHashMap<>();
        together.put(501L, red);
        together.put(502L, red);
        EventProblem refusal = refused(() -> service.assignTeams(G, id, together, u -> "Player " + u));
        assertTrue(refusal.problems().stream().anyMatch(p -> p.contains("share a team")), refusal.problems().toString());
        assertNull(player(id, 501).teamId());

        Map<Long, Long> apart = new LinkedHashMap<>();
        apart.put(501L, red);
        apart.put(502L, blue);
        assertEquals(2, service.assignTeams(G, id, apart, u -> "Player " + u));
    }

    @Test
    void theNamesInTheReasonsComeFromTheCaller() {
        long id = teamEvent("Names in reasons", "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\"}}");
        join(id, 501);
        long red = service.addTeam(G, id, "Red", null);
        EventProblem refusal = refused(() -> service.assignTeams(G, id, Map.of(501L, red), u -> "Drake"));
        assertEquals(List.of("Drake hasn't been verified, which this event needs before teams are made. Verify or waive them first."), refusal.problems());
    }

    // ---------- finalising ----------

    /** The roster has to be final before the teams can be. */
    private void closeAndConfirm(long id) {
        service.closeSignups(G, id, 9L);
        service.confirmRoster(G, id, 9L);
    }

    @Test
    void theTeamsAreFinalOnlyWhenEveryoneIsPlacedNoTeamIsEmptyAndTheRosterIsConfirmed() {
        long id = teamEvent("Final", "{\"setup\":{\"mode\":\"STANDARD\"}}");
        join(id, 501, 502, 503);
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);
        assertTrue(refused(() -> service.finaliseTeams(G, id, 9L)).getMessage().contains("Confirm the roster first"));
        closeAndConfirm(id);

        EventProblem unplaced = refused(() -> service.finaliseTeams(G, id, 9L));
        assertEquals(409, unplaced.status());
        assertTrue(unplaced.problems().contains("3 players still need a team."), unplaced.problems().toString());
        service.setTeam(G, id, 501, red);
        service.setTeam(G, id, 502, red);
        service.setTeam(G, id, 503, blue);
        long empty = service.addTeam(G, id, "Empty", null);
        assertTrue(refused(() -> service.finaliseTeams(G, id, 9L)).problems().contains("Team Empty has no players: add some or remove it."));
        service.removeTeam(G, id, empty);

        // the event can't start while the teams aren't final
        assertEquals(409, refused(() -> service.moveStage(G, id, Status.RUNNING)).status());
        service.finaliseTeams(G, id, 9L);
        assertTrue(repository.setup(id).teamsFinalised());
        assertEquals(9L, repository.setup(id).teamsFinalisedBy());
        assertEquals(409, refused(() -> service.finaliseTeams(G, id, 9L)).status(), "already final");
        var prep = service.prepOf(service.event(G, id));
        assertTrue(prep.ready(), prep.blockers().toString());

        // a player who joins late undoes 'ready' until they are on a team
        service.join(G, id, G, 504, null, true, null);
        assertFalse(service.prepOf(service.event(G, id)).ready());
        assertTrue(service.prepOf(service.event(G, id)).blockers().contains("1 player still needs a team."));
        service.setTeam(G, id, 504, blue);
        assertTrue(service.prepOf(service.event(G, id)).ready());
        service.moveStage(G, id, Status.RUNNING);
    }

    @Test
    void finalTeamsCanBeReopenedAndReopeningSignupsTakesThemBackToo() {
        long id = teamEvent("Reopen", "{\"setup\":{\"mode\":\"STANDARD\"}}");
        join(id, 501);
        long red = service.addTeam(G, id, "Red", null);
        service.setTeam(G, id, 501, red);
        closeAndConfirm(id);
        service.finaliseTeams(G, id, 9L);
        service.reopenTeams(G, id);
        assertFalse(repository.setup(id).teamsFinalised());
        assertEquals(409, refused(() -> service.reopenTeams(G, id)).status(), "they weren't final");
        service.finaliseTeams(G, id, 9L);
        service.reopenSignups(G, id);
        assertFalse(repository.setup(id).teamsFinalised(), "the teams were made from a roster that is about to change");
        assertFalse(repository.setup(id).rosterConfirmed());
    }

    @Test
    void anEventScoredByPlayerHasNoTeamsToFinalise() {
        long id = event("Solo", ScoringMode.INDIVIDUAL, "{}", 0);
        assertEquals(409, refused(() -> service.finaliseTeams(G, id, 9L)).status());
        assertTrue(service.prepOf(service.event(G, id)).steps().stream().noneMatch(s -> s.key().equals("teams")));
    }

    @Test
    void anEventNotFollowingTheSetupCanStartWithUnplacedPlayersButSaysSo() {
        long id = teamEvent("Loose", "{}");
        join(id, 501);
        service.addTeam(G, id, "Red", null);
        var prep = service.prepOf(service.event(G, id));
        assertTrue(prep.ready());
        assertTrue(prep.warnings().stream().anyMatch(w -> w.contains("still needs a team")), prep.warnings().toString());
    }

    // ---------- ratings ----------

    @Test
    void anOrganiserCanRateAPlayerOneToFiveAndClearTheRating() {
        long id = teamEvent("Ratings", "{}");
        join(id, 501);
        service.setRating(G, id, 501, 4);
        assertEquals(4, player(id, 501).rating());
        service.setRating(G, id, 501, null);
        assertNull(player(id, 501).rating());
        assertEquals(400, refused(() -> service.setRating(G, id, 501, 0)).status());
        assertEquals(400, refused(() -> service.setRating(G, id, 501, 6)).status());
        assertEquals(404, refused(() -> service.setRating(G, id, 999, 3)).status());
    }

    // ---------- what the builder knows ----------

    private static StatsSnapshotRow stats(long totalXp, int totalLevel) {
        return new StatsSnapshotRow(1L, OffsetDateTime.now(), totalLevel, totalXp, 138, 0, 0, 0);
    }

    @Test
    void aPlayersStrengthIsTheLatestStatsOfTheirLinkedNameWithTheMostXp() {
        long id = teamEvent("Strength", "{}");
        join(id, 501, 502, 503, 504);
        Mockito.when(links.getAllLinks(G)).thenReturn(List.of(
                new PlayerLink(1, G, 501, "Main", "x", null, null), new PlayerLink(2, G, 501, "Alt", "x", null, null),
                new PlayerLink(3, G, 502, "Zed", "x", null, null), new PlayerLink(4, G, 999, "Elsewhere", "x", null, null)));
        Mockito.when(links.getLatestSnapshots(ArgumentMatchers.anyCollection())).thenReturn(Map.of("main", stats(100, 1500), "alt", stats(900, 2800), "zed", stats(500, 2000)));

        var strengths = teams.strengths(G, List.of(501L, 502L, 503L));
        assertEquals("Alt", strengths.get(501L).rsn(), "the linked name with the most XP is their main");
        assertEquals(2800, strengths.get(501L).totalLevel());
        assertEquals(500L, strengths.get(502L).totalXp());
        assertFalse(strengths.containsKey(503L), "no link, no strength");
        assertTrue(teams.strengths(G, List.of()).isEmpty());

        service.setRating(G, id, 502, 5);
        var players = teams.players(G, service.event(G, id));
        assertEquals(4, players.size());
        assertEquals(5, players.stream().filter(p -> p.userId() == 502).findFirst().orElseThrow().rating());
        assertNull(players.stream().filter(p -> p.userId() == 501).findFirst().orElseThrow().rating());
    }

    @Test
    void aSuggestionIsMadeForPlayersWhoMayBePlacedAndRefusedForASoloEvent() {
        long id = teamEvent("Suggest", "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\"}}");
        join(id, 501, 502, 503, 504);
        for (long user : new long[]{501, 502, 503}) service.setVerification(G, id, user, Verification.VERIFIED, 9L, "");
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);
        service.setRating(G, id, 501, 5);
        service.setRating(G, id, 502, 5);
        service.setRating(G, id, 503, 1);

        var suggestion = teams.suggest(G, id);
        assertEquals(3, suggestion.moves().size(), "504 hasn't passed verification, so isn't placed");
        assertTrue(suggestion.moves().stream().noneMatch(m -> m.userId() == 504));
        // the two strongest are split, the weakest goes where it levels things (Red, first on a tie)
        assertEquals(List.of(new TeamBuilder.Move(501, red), new TeamBuilder.Move(502, blue), new TeamBuilder.Move(503, red)), suggestion.moves());
        assertTrue(repository.participants(id).stream().allMatch(p -> p.teamId() == null), "a suggestion changes nothing");

        long solo = event("Solo suggest", ScoringMode.INDIVIDUAL, "{}", 0);
        assertEquals(409, refused(() -> teams.suggest(G, solo)).status());
    }
}
