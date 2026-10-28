package com.runeclans.bot.events;

import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The co-host panel and pending-join records over real SQL (T-221). Runs over the developer's local Postgres (skipped without one). */
class PendingJoinDatabaseTest {
    private static final long HOST = 999_000_000_000_000_081L, ALLY = 999_000_000_000_000_082L, OTHER_HOST = 999_000_000_000_000_083L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventCohostRepository cohosts;
    private EventService service;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        cohosts = new EventCohostRepository(supplier);
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_OTHER;
            }
        }, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id IN (?, ?)")) {
            s.setLong(1, HOST);
            s.setLong(2, OTHER_HOST);
            s.executeUpdate();
        }
    }

    private long event(long host) {
        return service.createEvent(host, 9L, new EventService.EventInput("Joint event", "", "custom", EventRules.Scope.ALLIANCE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.empty()));
    }

    @Test
    void pendingJoinsAreFoundOnlyForTheHostTheyWaitForAndOnlyUntilTheyExpire() {
        long mine = event(HOST), theirs = event(OTHER_HOST);
        Instant now = Instant.parse("2026-10-22T10:00:00Z");
        cohosts.savePendingJoin(mine, 7, ALLY, now.plusSeconds(3_600));
        cohosts.savePendingJoin(theirs, 7, ALLY, now.plusSeconds(3_600));
        assertEquals(List.of(mine), cohosts.pendingJoins(7, HOST, now).stream().map(EventCohostRepository.PendingJoin::eventId).toList());
        assertTrue(cohosts.pendingJoins(7, HOST, now.plusSeconds(3_601)).isEmpty(), "expired");

        cohosts.savePendingJoin(mine, 7, ALLY, now.plusSeconds(7_200)); // pressing Join again renews it
        assertEquals(1, cohosts.pendingJoins(7, HOST, now.plusSeconds(3_601)).size());
        cohosts.deletePendingJoins(mine, ALLY);
        assertTrue(cohosts.pendingJoins(7, HOST, now).isEmpty());
        assertEquals(1, cohosts.purgeExpiredPendingJoins(now.plusSeconds(4_000)), "the other host's one has expired by then");
    }

    @Test
    void aCoHostsPanelChannelAndMessageAreKeptWithIt() {
        long id = event(HOST);
        cohosts.invite(id, ALLY, 9L);
        cohosts.decide(id, ALLY, EventCohostRepository.Status.INVITED, EventCohostRepository.Status.ACCEPTED, 8L);
        assertTrue(cohosts.panels(id).isEmpty(), "no channel chosen yet");
        cohosts.setPanelChannel(id, ALLY, 555L);
        cohosts.setPanelMessage(id, ALLY, 777L);
        var panel = cohosts.panels(id).get(0);
        assertEquals(555L, panel.channelId());
        assertEquals(777L, panel.messageId());
        cohosts.setPanelMessage(id, ALLY, null);
        assertEquals(null, cohosts.panel(id, ALLY).orElseThrow().messageId());
    }
}
