package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * XP tiles (T-543) over a real {@link EventService} and {@link EventRepository} on the developer's local Postgres (skipped without one): a tile
 * fills and completes from rising gains, a re-read adds nothing, two passes that read the same counted total (two bot instances at once)
 * write only once, and a team's members' gains add up. The hub is mocked; everything is made in a made-up server and removed afterwards.
 */
class XpTilesDatabaseTest {
    private static final long G = Long.parseLong("9".repeat(15) + "543");
    private static final long ALICE = 501;
    private static final long BOB = 502;
    private static final int FLETCHING = 9;
    private static final int MINING = 14;
    private static final ObjectiveSpec MINING_TILE = new ObjectiveSpec("item-1", "500k XP in Mining", "", 10, Evidence.EITHER, 500_000, 1, List.of());
    private static final ObjectiveSpec FLETCHING_TILE = new ObjectiveSpec("item-2", "Gain 1M Fletching XP", "", 10, Evidence.EITHER, 1, 1, List.of());
    private static final ObjectiveSpec OTHER = new ObjectiveSpec("item-3", "Nex: Torva boots", "", 10, Evidence.EITHER, 1, 1, List.of());
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);

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
        service = new EventService(repository, new EventTemplates(List.of(new ScavengerHuntTemplate())), kinds,
                new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    /** A tracker reading through {@code reads}, an hour ahead so the hunt has been running a while. */
    private PluginXpTracker tracker(EventRepository reads) {
        return new PluginXpTracker(reads, service, hub).withClock(Clock.offset(Clock.systemUTC(), Duration.ofHours(1))).withTiles(true);
    }

    private EventRepository.Event runningHunt() {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Hunt", "", ScavengerHuntTemplate.KEY, Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.fromJson("{\"steps\":3}")));
        service.setObjectives(G, id, List.of(MINING_TILE, FLETCHING_TILE, OTHER));
        service.moveStage(G, id, Status.SIGNUPS);
        service.join(G, id, G, ALICE, null, true, null);
        service.moveStage(G, id, Status.RUNNING);
        return repository.eventById(id).orElseThrow();
    }

    private void hubSays(long mining, long fletching) {
        DataArray bySkill = DataArray.empty().add(DataObject.empty().put("skillId", MINING).put("xp", mining)).add(DataObject.empty().put("skillId", FLETCHING).put("xp", fletching));
        DataObject row = DataObject.empty().put("alias", "a1_alice").put("xp", mining + fletching).put("bySkill", bySkill);
        when(hub.xpGains(eq(G), any(), isNull(), any(), any())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", DataArray.empty().add(row))
                .put("notCounted", DataArray.empty())));
    }

    private long total(EventRepository.Event e, ObjectiveSpec tile) {
        return repository.total(e.id(), tile.key(), ALICE, false);
    }

    @Test
    void tilesFillAndCompleteFromRisingGainsAndARereadAddsNothing() {
        EventRepository.Event e = runningHunt();
        PluginXpTracker tracker = tracker(repository);

        hubSays(300_000, 600_000);
        assertEquals(1, tracker.feedTiles(e));
        assertEquals(300_000, total(e, MINING_TILE));
        assertEquals(0, EventScoring.completions(MINING_TILE, total(e, MINING_TILE)));
        assertEquals(0, total(e, FLETCHING_TILE));

        hubSays(520_000, 1_100_000);
        assertEquals(2, tracker.feedTiles(e));
        assertEquals(500_000, total(e, MINING_TILE), "never more than the tile holds");
        assertEquals(1, EventScoring.completions(MINING_TILE, total(e, MINING_TILE)));
        assertEquals(1, EventScoring.completions(FLETCHING_TILE, total(e, FLETCHING_TILE)));
        assertEquals(0, total(e, OTHER));

        assertEquals(0, tracker.feedTiles(e), "the same reading again adds nothing");
        EventScoring.Standing alice = service.standings(G, e.id()).stream().filter(s -> s.subject().equals("u:" + ALICE)).findFirst().orElseThrow();
        assertEquals(1, alice.completions().get(MINING_TILE.key()));
        assertEquals(1, alice.completions().get(FLETCHING_TILE.key()));
    }

    @Test
    void twoPassesBuildingOnTheSameTotalWriteOnlyOnce() {
        EventRepository.Event e = runningHunt();
        hubSays(300_000, 0);
        tracker(repository).feedTiles(e);
        List<EventRepository.Progress> before = repository.progress(e.id());

        // a second bot instance read the progress before the first one wrote, then sees a slightly later gain
        EventRepository stale = Mockito.spy(repository);
        Mockito.doReturn(before).when(stale).progress(e.id());
        hubSays(400_000, 0);
        assertEquals(1, tracker(repository).feedTiles(e));
        hubSays(410_000, 0);
        assertEquals(0, tracker(stale).feedTiles(e), "built on the same 300,000: refused");
        assertEquals(400_000, total(e, MINING_TILE));
    }

    @Test
    void aTeamCompletesATileFromItsMembersGainsAddedUp() {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Team hunt", "", ScavengerHuntTemplate.KEY, Scope.PRIVATE, ScoringMode.TEAM, null, null, 0,
                DataObject.fromJson("{\"steps\":3}")));
        service.setObjectives(G, id, List.of(MINING_TILE, FLETCHING_TILE, OTHER));
        long red = service.addTeam(G, id, "Red", null);
        service.moveStage(G, id, Status.SIGNUPS);
        service.join(G, id, G, ALICE, red, true, null);
        service.join(G, id, G, BOB, red, true, null);
        service.moveStage(G, id, Status.RUNNING);
        EventRepository.Event e = repository.eventById(id).orElseThrow();
        DataArray players = DataArray.empty();
        for (String alias : List.of("a1_alice", "a1_bob")) {
            players.add(DataObject.empty().put("alias", alias).put("xp", 600_000L).put("bySkill", DataArray.empty().add(DataObject.empty().put("skillId", FLETCHING).put("xp", 600_000L))));
        }
        when(hub.xpGains(eq(G), any(), isNull(), any(), any())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players)));

        assertEquals(1, tracker(repository).feedTiles(e), "600k each: neither alone reaches 1M, the team does");
        assertEquals(1, repository.total(id, FLETCHING_TILE.key(), red, true));
        EventScoring.Standing team = service.standings(G, id).stream().filter(s -> s.subject().equals("t:" + red)).findFirst().orElseThrow();
        assertEquals(1, team.completions().get(FLETCHING_TILE.key()));
        assertEquals(0, tracker(repository).feedTiles(e), "complete: not asked again");
    }
}
