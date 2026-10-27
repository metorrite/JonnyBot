package com.younglings.bot.internal;

import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.EventDraws;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.WinModes;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event manager's Win section routes (T-649) over the local Postgres: reading and saving the win rules, refusing what can't be saved, Draw now and
 * Reroll reaching the draw, and the draw shown with its seed and entries. Skipped where there is no local database.
 */
class EventWinApiTest {
    private static final long G = 649_650L;

    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventDraws draws;
    private final List<WinModes.Round> rounds = new ArrayList<>();
    private EventWinApi api;
    private Guild guild;
    private Member actor;
    private long bingo;
    private long custom;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventService service = Mockito.mock(EventService.class);
        Mockito.when(service.event(ArgumentMatchers.eq(G), ArgumentMatchers.anyLong())).thenAnswer(inv -> repository.event(G, inv.getArgument(1)).orElseThrow());
        Mockito.when(service.objectives(ArgumentMatchers.anyLong())).thenReturn(List.of());
        Mockito.when(service.prizes(ArgumentMatchers.anyLong())).thenReturn(List.of());
        draws = Mockito.mock(EventDraws.class);
        Mockito.when(draws.rounds(ArgumentMatchers.anyLong())).thenAnswer(inv -> List.copyOf(rounds));
        api = new EventWinApi(service, repository, draws);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getId()).thenReturn("9");
        Mockito.when(actor.getIdLong()).thenReturn(9L);
        bingo = repository.createEvent(G, BingoTemplate.KEY, "Advent bingo", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, "{\"board\":{\"size\":5}}", 1L);
        custom = repository.createEvent(G, "custom", "Boss night", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, "{}", 1L);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private DataObject call(String method, long id, String body, String... more) {
        String[] parts = new String[3 + more.length];
        parts[0] = "events";
        parts[1] = String.valueOf(id);
        parts[2] = "win";
        System.arraycopy(more, 0, parts, 3, more.length);
        return api.route(guild, actor, method, parts, () -> DataObject.fromJson(body));
    }

    @Test
    void aNewEventIsWonOnPointsAsToday() {
        DataObject view = call("GET", bingo, "{}");
        assertTrue(view.getBoolean("isDefault"));
        assertEquals("POINTS", view.getObject("settings").getString("mode"));
        assertTrue(view.getBoolean("canChange"));
        assertFalse(view.getBoolean("mayDraw"));
        assertTrue(view.isNull("draw"));
    }

    @Test
    void savedRulesAreKeptAndPointsTakesThemOutAgain() {
        DataObject view = call("PUT", bingo, "{\"mode\":\"DRAW\",\"minTiles\":3,\"entries\":\"PER_PLAYER\",\"winners\":2}");
        assertFalse(view.getBoolean("isDefault"));
        WinModes.Settings saved = WinModes.of(DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()));
        assertEquals(new WinModes.Settings(WinModes.Mode.DRAW, WinModes.Entries.PER_PLAYER, 3, 2, true), saved);
        assertEquals(5, DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()).getObject("board").getInt("size"), "the rest of the settings are kept");
        call("PUT", bingo, "{\"mode\":\"POINTS\"}");
        assertFalse(DataObject.fromJson(repository.event(G, bingo).orElseThrow().config()).hasKey("win"));
    }

    @Test
    void whatCannotBeSavedIsRefused() {
        assertEquals(400, assertThrows(ApiError.class, () -> call("PUT", custom, "{\"mode\":\"FIRST_LINE\"}")).status, "lines need a bingo");
        assertEquals(400, assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"mode\":\"DRAW\",\"winners\":0}")).status);
        rounds.add(new WinModes.Round(0, 1, List.of(), List.of(), null, null, Instant.now(), null));
        assertEquals(409, assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"mode\":\"POINTS\"}")).status, "not once drawn");
        rounds.clear();
        repository.moveStage(G, bingo, Status.DRAFT, Status.DONE);
        assertEquals(409, assertThrows(ApiError.class, () -> call("PUT", bingo, "{\"mode\":\"DRAW\"}")).status);
    }

    @Test
    void drawNowAndRerollReachTheDrawAndTheResultIsShownWithItsSeed() {
        call("PUT", bingo, "{\"mode\":\"DRAW\"}");
        repository.moveStage(G, bingo, Status.DRAFT, Status.RUNNING);
        assertTrue(call("GET", bingo, "{}").getBoolean("mayDraw"));
        call("POST", bingo, "{}", "draw");
        Mockito.verify(draws).drawNow(guild, bingo, 9L);
        rounds.add(new WinModes.Round(0, -3, List.of(new WinModes.Entrant("t:1", 2, 2)), List.of("t:1"), null, null, Instant.now(), null));
        call("POST", bingo, "{\"place\":1}", "reroll");
        Mockito.verify(draws).reroll(guild, bingo, 1, 9L);
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", bingo, "{\"place\":\"first\"}", "reroll")).status);

        DataObject draw = call("GET", bingo, "{}").getObject("draw");
        assertEquals("A team", draw.getArray("winners").getObject(0).getString("name"));
        DataObject round = draw.getArray("rounds").getObject(0);
        assertEquals(Long.toUnsignedString(-3), round.getString("seed"));
        assertTrue(round.getBoolean("auto"));
        assertEquals(2, round.getInt("tickets"));
    }

    @Test
    void otherPathsAreLeftToOtherRoutes() {
        assertNull(api.route(guild, actor, "GET", new String[] {"events", String.valueOf(bingo), "updates"}, DataObject::empty));
        assertNull(api.route(guild, actor, "POST", new String[] {"events", String.valueOf(bingo), "win", "other"}, DataObject::empty));
    }
}
