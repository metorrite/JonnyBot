package com.younglings.bot.events;

import com.younglings.bot.events.EventPrep.Step;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setup phases of an event (signups, roster, teams) hold together both ways, over a real database (skipped without one): changing who is in the event
 * after the roster was confirmed, or the teams after they were called final, makes the checklist ask for the confirmation again instead of silently keeping
 * a stale one, and the confirmation can be given again. Going back (reopening signups) takes the later steps back with it.
 */
class EventPhaseSafeguardsTest {
    private static final long G = 999_000_000_000_000_091L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long teamEvent() {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Phases", "", "custom", Scope.PRIVATE, ScoringMode.TEAM, null, null, 0, DataObject.fromJson("{\"setup\":{\"mode\":\"STANDARD\"}}")));
        service.setObjectives(G, id, List.of(new ObjectiveSpec("kill", "Kill it", "", 10, Evidence.EITHER, 1, 1, List.of())));
        service.moveStage(G, id, Status.SIGNUPS);
        return id;
    }

    private Step step(long id, String key) {
        return service.prepOf(service.event(G, id)).steps().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void aPlayerJoiningAfterTheRosterWasConfirmedMakesTheChecklistAskAgain() {
        long id = teamEvent();
        service.join(G, id, G, 501, null, true, null);
        service.closeSignups(G, id, 9L);
        service.confirmRoster(G, id, 9L);
        assertEquals(EventPrep.State.DONE, step(id, "roster").state());

        service.join(G, id, G, 502, null, true, null);
        Step roster = step(id, "roster");
        assertEquals(EventPrep.State.ACTIVE, roster.state(), "a late add: the confirmation is out of date");
        assertTrue(roster.detail().contains("changed after you confirmed"), roster.detail());
        assertTrue(service.prepOf(service.event(G, id)).blockers().contains("Confirm the roster again."));

        service.confirmRoster(G, id, 9L);
        assertEquals(EventPrep.State.DONE, step(id, "roster").state());
        assertEquals(409, assertThrows(EventProblem.class, () -> service.confirmRoster(G, id, 9L)).status(), "nothing changed since: still refused");
    }

    @Test
    void aPlayerLeavingOrBeingRemovedAlsoOutdatesTheConfirmation() {
        long id = teamEvent();
        service.join(G, id, G, 501, null, true, null);
        service.join(G, id, G, 502, null, true, null);
        service.closeSignups(G, id, 9L);
        service.confirmRoster(G, id, 9L);
        service.leave(G, id, 502);
        assertEquals(EventPrep.State.ACTIVE, step(id, "roster").state());
        service.confirmRoster(G, id, 9L);
        service.ban(G, id, 501, "no reason needed here", 9L);
        assertEquals(EventPrep.State.ACTIVE, step(id, "roster").state(), "a removal with a ban counts the same");
    }

    @Test
    void movingAPlayerAfterTheTeamsWereFinalMakesThemAskToBeFinalisedAgain() {
        long id = teamEvent();
        service.join(G, id, G, 501, null, true, null);
        service.join(G, id, G, 502, null, true, null);
        long red = service.addTeam(G, id, "Red", null);
        long blue = service.addTeam(G, id, "Blue", null);
        service.closeSignups(G, id, 9L);
        service.confirmRoster(G, id, 9L);
        service.setTeam(G, id, 501, red);
        service.setTeam(G, id, 502, blue);
        service.finaliseTeams(G, id, 9L);
        assertEquals(EventPrep.State.DONE, step(id, "teams").state());

        service.setTeam(G, id, 502, red);
        service.removeTeam(G, id, blue);
        Step teams = step(id, "teams");
        assertEquals(EventPrep.State.ACTIVE, teams.state());
        assertTrue(teams.detail().contains("changed after you called them final"), teams.detail());
        assertTrue(service.prepOf(service.event(G, id)).blockers().contains("Finalise the teams again."));

        service.finaliseTeams(G, id, 9L);
        assertEquals(EventPrep.State.DONE, step(id, "teams").state());
        assertEquals(409, assertThrows(EventProblem.class, () -> service.finaliseTeams(G, id, 9L)).status(), "unchanged: already final");
    }

    @Test
    void anEventNotManagedByTheChecklistIsNotHeldUp() {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Casual", "", "custom", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson("{}")));
        service.setObjectives(G, id, List.of(new ObjectiveSpec("kill", "Kill it", "", 10, Evidence.EITHER, 1, 1, List.of())));
        service.moveStage(G, id, Status.SIGNUPS);
        service.join(G, id, G, 501, null, true, null);
        assertTrue(service.prepOf(service.event(G, id)).ready(), "start whenever you like");
    }

    @Test
    void reopeningSignupsTakesTheLaterStepsBackAndAClosedRosterIsNotChangedByAVerificationOnAnotherEvent() {
        long id = teamEvent();
        service.join(G, id, G, 501, null, true, null);
        service.closeSignups(G, id, 9L);
        service.confirmRoster(G, id, 9L);
        service.reopenSignups(G, id);
        assertFalse(repository.setup(id).rosterConfirmed(), "the confirmation goes with the closed signups");
        assertEquals(EventPrep.State.ACTIVE, step(id, "signups").state());
        service.closeSignups(G, id, 9L);
        service.confirmRoster(G, id, 9L);
        assertEquals(EventPrep.State.DONE, step(id, "roster").state(), "confirmed fresh: no change since");
    }
}
