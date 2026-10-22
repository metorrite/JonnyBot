package com.younglings.bot.events;

import com.younglings.bot.commands.signup.SignupService;
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
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seat quotas over real SQL (T-220): a join beyond the server's quota waits even with places free, a staff late add ignores the quota, a freed place
 * goes to the next player whose server has room, and the release in the signup sweep fills the rest. Runs over the developer's local Postgres (skipped
 * without one).
 */
class FillQueueDatabaseTest {
    private static final long HOST = 999_000_000_000_000_061L, ALLY = 999_000_000_000_000_062L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventCohostRepository cohosts;
    private EventService service;
    private EventSignups signups;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        cohosts = new EventCohostRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return userGuildId == hostGuildId ? TeamRules.Kind.HOME_CLAN : TeamRules.Kind.PARTNER;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        signups = new EventSignups(repository, service, Mockito.mock(SignupService.class), new EventChanges());
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, HOST);
            s.executeUpdate();
        }
    }

    /** An event taking signups with {@code max} places, the host's quota in its settings and the ally as an accepted co-host with its own quota. */
    private long event(int max, String config, Integer allyQuota) {
        long id = service.createEvent(HOST, 9L, new EventService.EventInput("Quota test", "", "custom", EventRules.Scope.ALLIANCE, EventRules.ScoringMode.INDIVIDUAL, null, null, max,
                DataObject.fromJson(config)));
        service.moveStage(HOST, id, EventRules.Status.SIGNUPS);
        cohosts.invite(id, ALLY, 9L);
        cohosts.decide(id, ALLY, EventCohostRepository.Status.INVITED, EventCohostRepository.Status.ACCEPTED, 8L);
        java.util.Map<Long, Integer> quotas = new java.util.HashMap<>();
        quotas.put(ALLY, allyQuota);
        cohosts.setQuotas(id, quotas);
        return id;
    }

    private boolean waiting(long id, long user) {
        return repository.participant(id, user).orElseThrow().waiting();
    }

    @Test
    void aPlayerBeyondTheirServersQuotaWaitsEvenWithPlacesFreeButStaffCanAddThem() {
        long id = event(4, "{\"queueQuota\":{\"host\":2},\"signupClosesAt\":\"2099-01-01T00:00:00Z\"}", 2);
        assertEquals(Map.of(ALLY, 2), repository.cohostQuotas(id));

        assertFalse(service.join(HOST, id, HOST, 1, null, false, null).waiting());
        assertFalse(service.join(HOST, id, HOST, 2, null, false, null).waiting());
        var third = service.join(HOST, id, HOST, 3, null, false, null);
        assertTrue(third.waiting(), "the host's 2 places are taken, so a third host player waits although 2 places are free");
        assertEquals(1, third.position());
        assertFalse(service.join(HOST, id, ALLY, 4, null, false, null).waiting(), "the co-host still has room");
        assertFalse(service.join(HOST, id, HOST, 5, null, true, null).waiting(), "a staff late add ignores the quota");
    }

    @Test
    void aFreedPlaceGoesToTheNextPlayerWhoseServerHasRoom() {
        long id = event(3, "{\"queueQuota\":{\"host\":2},\"signupClosesAt\":\"2099-01-01T00:00:00Z\"}", 1);
        service.join(HOST, id, HOST, 1, null, false, null);
        service.join(HOST, id, HOST, 2, null, false, null);
        service.join(HOST, id, ALLY, 3, null, false, null); // full now
        service.join(HOST, id, HOST, 4, null, false, null); // waits: full, and the host is at its quota
        service.join(HOST, id, ALLY, 5, null, false, null); // waits: full

        service.leave(HOST, id, 3); // an ally place frees up: host player 4 joined first but the host has no room, so ally player 5 moves in
        assertTrue(waiting(id, 4));
        assertFalse(waiting(id, 5));
    }

    @Test
    void theReleaseInTheSignupSweepFillsPlacesAServerLeftUnused() {
        long id = event(4, "{\"queueQuota\":{\"host\":1,\"releaseHours\":24},\"signupClosesAt\":\"2099-01-01T00:00:00Z\"}", 3);
        service.join(HOST, id, HOST, 1, null, false, null);
        service.join(HOST, id, HOST, 2, null, false, null);
        service.join(HOST, id, HOST, 3, null, false, null);
        assertTrue(waiting(id, 2) && waiting(id, 3), "the ally's 3 places are kept for it until the release");

        signups.releaseQuotas();
        assertTrue(waiting(id, 2), "not released yet: the deadline is far away");

        DataObject config = DataObject.fromJson(repository.event(HOST, id).orElseThrow().config()).put("signupClosesAt", "2026-01-01T00:00:00Z");
        repository.setConfig(HOST, id, config.toString());
        signups.releaseQuotas();
        assertFalse(waiting(id, 2));
        assertFalse(waiting(id, 3));
    }

    @Test
    void anEventWithoutQuotasFillsExactlyAsBefore() {
        long id = event(1, "{}", null);
        service.join(HOST, id, HOST, 1, null, false, null);
        assertTrue(service.join(HOST, id, ALLY, 2, null, false, null).waiting());
        service.leave(HOST, id, 1);
        assertFalse(waiting(id, 2));
    }
}
