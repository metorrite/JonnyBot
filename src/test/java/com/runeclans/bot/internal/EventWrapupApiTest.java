package com.runeclans.bot.internal;


import com.runeclans.bot.events.CustomTemplate;
import com.runeclans.bot.events.EventChanges;
import com.runeclans.bot.events.EventKinds;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventSpaceOps;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.events.EventWrapup;
import com.runeclans.bot.events.EventWrapupOps;
import com.runeclans.bot.events.EventWrapupRepository;
import com.runeclans.bot.events.TeamRules;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wrap-up's dashboard routes and its bookkeeping over a real database (skipped without one): what the page shows, that every deleting action needs
 * an explicit "yes", that the settings are checked and kept, and that a step is noted once. The Discord work itself is mocked; the pure rules are in
 * {@link EventWrapupTest}.
 */
class EventWrapupApiTest {
    private static final long G = 999_000_000_000_000_071L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private EventWrapupRepository wrapup;
    private EventWrapupOps wrapupOps;
    private EventSpaceOps spaceOps;
    private EventWrapupApi api;
    private Guild guild;
    private Member actor;

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
        wrapup = new EventWrapupRepository(supplier, repository);
        wrapupOps = Mockito.mock(EventWrapupOps.class);
        spaceOps = Mockito.mock(EventSpaceOps.class);
        Mockito.when(wrapupOps.counts(Mockito.anyLong())).thenReturn(new EventWrapupOps.Counts(3, 1, 2, 0));
        api = new EventWrapupApi(service, wrapupOps, spaceOps, wrapup);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getId()).thenReturn("9");
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long event(String config) {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Wrap", "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson(config)));
        service.setObjectives(G, id, List.of(new EventRules.ObjectiveSpec("kill", "Kill it", "", 10, EventRules.Evidence.EITHER, 1, 1, List.of())));
        return id;
    }

    private void finish(long id) {
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        service.moveStage(G, id, EventRules.Status.RUNNING);
        service.moveStage(G, id, EventRules.Status.SCORING);
        service.moveStage(G, id, EventRules.Status.DONE);
    }

    private DataObject call(String method, long id, String path, DataObject body) {
        String[] parts = (("events/" + id + "/wrapup") + (path.isEmpty() ? "" : "/" + path)).split("/");
        return api.route(guild, actor, method, parts, () -> body);
    }

    @Test
    void thePageShowsTheDefaultsTheCountsAndWhatIsNotDoneYet() {
        long id = event("{}");
        DataObject view = call("GET", id, "", DataObject.empty());
        assertFalse(view.getBoolean("over"), "still going");
        assertEquals(7, view.getObject("settings").getInt("rolesDays"));
        assertEquals(-1, view.getObject("settings").getInt("channelsDays"));
        assertEquals(3, view.getObject("counts").getInt("channels"));
        assertTrue(view.getObject("state").isNull("finishedAt"));
        assertTrue(view.isNull("job"));
        assertTrue(view.isNull("export"));
    }

    @Test
    void routesOfOtherPartsOfTheDashboardAreNotThisOnes() {
        long id = event("{}");
        assertNull(api.route(guild, actor, "GET", new String[]{"events", Long.toString(id), "space"}, DataObject::empty));
        assertNull(call("POST", id, "dance", DataObject.empty()));
    }

    @Test
    void everyActionThatDeletesNeedsAnExplicitYes() {
        long id = event("{}");
        finish(id);
        for (String action : new String[]{"roles", "archive", "delete"}) {
            ApiError refused = assertThrows(ApiError.class, () -> call("POST", id, action, DataObject.empty()), action);
            assertEquals(400, refused.status, action);
        }
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", id, "compress", DataObject.empty().put("deleteOriginals", true))).status, "compressing and deleting the originals");
        Mockito.verify(wrapupOps, Mockito.never()).startRoles(Mockito.any(), Mockito.any());
        Mockito.verify(wrapupOps, Mockito.never()).startArchive(Mockito.any(), Mockito.any());
        Mockito.verify(wrapupOps, Mockito.never()).startDelete(Mockito.any(), Mockito.any());

        call("POST", id, "roles", DataObject.empty().put("confirm", true));
        Mockito.verify(wrapupOps).startRoles(Mockito.eq(guild), Mockito.any());
        call("POST", id, "lock", DataObject.empty());
        Mockito.verify(wrapupOps).startLock(Mockito.eq(guild), Mockito.any());
        call("POST", id, "compress", DataObject.empty().put("all", true));
        Mockito.verify(wrapupOps).startCompress(Mockito.eq(guild), Mockito.any(), Mockito.eq(true), Mockito.eq(false));
        call("POST", id, "export", DataObject.empty());
        Mockito.verify(wrapupOps).startExport(Mockito.eq(guild), Mockito.any());
    }

    @Test
    void theSettingsAreCheckedAndKeptEvenAfterTheEventHasFinished() {
        long id = event("{}");
        finish(id);
        EventProblem bad = assertThrows(EventProblem.class, () -> service.setWrapupSettings(G, id, DataObject.fromJson("{\"rolesDays\":-9}")));
        assertEquals(400, bad.status());
        DataObject saved = call("PUT", id, "settings", DataObject.fromJson("{\"lockOnFinish\":false,\"rolesDays\":3,\"channelsDays\":14,\"channelsAction\":\"DELETE\"}"));
        assertTrue(saved.getBoolean("over"));
        assertFalse(saved.getObject("settings").getBoolean("lockOnFinish"));
        assertEquals(3, saved.getObject("settings").getInt("rolesDays"));
        assertEquals("DELETE", saved.getObject("settings").getString("channelsAction"));
        assertEquals(14, EventWrapup.parse(DataObject.fromJson(service.event(G, id).config())).channelsDays());
    }

    @Test
    void theEndIsNotedOnceAStepIsRecordedAndReopeningStartsOver() {
        long id = event("{}");
        assertTrue(wrapup.noteFinished(id));
        var first = wrapup.row(id).state().finishedAt();
        assertNotNull(first);
        assertFalse(wrapup.noteFinished(id), "noted once");
        assertEquals(first, wrapup.row(id).state().finishedAt());

        wrapup.done(id, EventWrapup.Step.LOCK, null);
        wrapup.done(id, EventWrapup.Step.CHANNELS, "archived");
        wrapup.exported(id);
        var row = wrapup.row(id);
        assertNotNull(row.state().lockedAt());
        assertNull(row.state().rolesRemovedAt());
        assertEquals("archived", row.channelsHow());
        assertNotNull(row.exportedAt());
        assertEquals(first, row.state().finishedAt(), "doing a step doesn't move the end");

        DataObject view = call("GET", id, "", DataObject.empty());
        assertEquals("archived", view.getObject("state").getString("channelsHow"));
        assertTrue(view.getObject("schedule").isNull("lockAt"), "locking is already done");
        assertNotNull(view.getObject("schedule").getString("rolesAt"), "the roles are still to go, a week after the end");

        wrapup.clear(id);
        assertNull(wrapup.row(id).state().finishedAt());
    }

    @Test
    void anExportThatWasNotMadeCannotBeDownloaded() {
        long id = event("{}");
        ApiError none = assertThrows(ApiError.class, () -> call("GET", id, "export/file", DataObject.empty()));
        assertEquals(404, none.status);
        Mockito.when(wrapupOps.export(id)).thenReturn(new EventWrapupOps.Export("e.zip", new byte[]{1, 2, 3}, java.time.Instant.now(), 1, 2, 0, 0));
        DataObject file = call("GET", id, "export/file", DataObject.empty());
        assertEquals("e.zip", file.getString("name"));
        assertEquals(3, file.getInt("size"));
        assertEquals("AQID", file.getString("base64"));
    }
}
