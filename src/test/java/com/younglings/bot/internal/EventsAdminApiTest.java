package com.younglings.bot.internal;

import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.BossNightTemplate;
import com.younglings.bot.events.CompetitionTemplate;
import com.younglings.bot.events.CustomTemplate;
import com.younglings.bot.events.EventBoardPanels;
import com.younglings.bot.events.EventChanges;
import com.younglings.bot.events.EventKinds;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventSignups;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.ScavengerHuntTemplate;
import com.younglings.bot.events.TeamRules;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event routes end to end: each test sends a request to {@link EventsAdminApi} exactly as the dashboard does and reads the answer, over a real
 * {@link EventService} and {@link EventRepository} on the developer's local Postgres. Skipped where there is no local database. Everything is made in
 * two made-up servers and removed afterwards.
 */
class EventsAdminApiTest {
    private static final long G = 999_000_000_000_000_011L;
    private static final long OTHER = 999_000_000_000_000_012L;
    private static final long ACTOR = 9L;

    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private EventsAdminApi api;
    private com.younglings.bot.runescape.PlayerLinkRepository links;
    private Guild guild;
    private Member actor;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        var templates = new EventTemplates(List.of(new CustomTemplate(), new BingoTemplate(), new ScavengerHuntTemplate(), new CompetitionTemplate(), new BossNightTemplate()));
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        var points = new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier));
        service = new EventService(repository, templates, kinds, points, new EventChanges());
        EventSignups signups = Mockito.mock(EventSignups.class);
        Mockito.when(signups.stateOf(ArgumentMatchers.any())).thenReturn(new EventSignups.State(false, false, 0, false));
        EventBoardPanels panels = Mockito.mock(EventBoardPanels.class);
        Mockito.when(panels.refresh(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenReturn("Posted the board.");
        links = Mockito.mock(com.younglings.bot.runescape.PlayerLinkRepository.class);
        api = new EventsAdminApi(service, repository, templates, signups, new BingoTemplate(), panels, links, new com.younglings.bot.events.BingoTiles(new com.younglings.bot.internal.WikiCatalog()));

        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Member member = Mockito.mock(Member.class);
        User user = Mockito.mock(User.class);
        Mockito.when(user.isBot()).thenReturn(false);
        Mockito.when(member.getUser()).thenReturn(user);
        Mockito.when(member.getEffectiveName()).thenReturn("Tester");
        Mockito.when(guild.getMemberById(ArgumentMatchers.anyLong())).thenReturn(member);
        actor = Mockito.mock(Member.class);
        Mockito.when(actor.getIdLong()).thenReturn(ACTOR);
        Mockito.when(actor.getId()).thenReturn(Long.toString(ACTOR));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id IN (?, ?)")) {
            s.setLong(1, G);
            s.setLong(2, OTHER);
            s.executeUpdate();
        }
    }

    // ---------- helpers ----------

    private DataObject call(String method, String path, String json) {
        String[] parts = path.split("/");
        return api.route(guild, actor, method, parts, () -> json == null ? DataObject.empty() : DataObject.fromJson(json));
    }

    private ApiError failure(String method, String path, String json) {
        return assertThrows(ApiError.class, () -> call(method, path, json));
    }

    private String create(String json) {
        return call("POST", "events", json).getObject("event").getString("id");
    }

    private static long count(DataArray array) {
        return array.length();
    }

    // ---------- reading ----------

    @Test
    void theTemplateListHasEveryKind() {
        var keys = new ArrayList<String>();
        var templates = call("GET", "events/templates", null).getArray("templates");
        for (int i = 0; i < templates.length(); i++) keys.add(templates.getObject(i).getString("key"));
        assertTrue(keys.containsAll(List.of("custom", "bingo", "scavenger-hunt", "competition", "boss-night")), keys.toString());
    }

    @Test
    void aRouteThatIsntAnEventRouteIsLeftAlone() {
        assertNull(api.route(guild, actor, "GET", new String[]{"tickets"}, DataObject::empty));
    }

    @Test
    void theListShowsOnlyThisServersEvents() {
        create("{\"name\":\"Mine one\"}");
        service.createEvent(OTHER, 9L, new EventService.EventInput("Theirs", "", "custom", com.younglings.bot.events.EventRules.Scope.PRIVATE, com.younglings.bot.events.EventRules.ScoringMode.INDIVIDUAL, null, null, 0, null));
        var events = call("GET", "events", null).getArray("events");
        assertEquals(1, count(events));
        assertEquals("Mine one", events.getObject(0).getString("name"));
    }

    @Test
    void anotherServersEventIsNotFoundHereOnAnyRoute() {
        long theirs = service.createEvent(OTHER, 9L, new EventService.EventInput("Theirs", "", "custom", com.younglings.bot.events.EventRules.Scope.PRIVATE, com.younglings.bot.events.EventRules.ScoringMode.INDIVIDUAL, null, null, 0, null));
        for (String path : List.of("events/" + theirs, "events/" + theirs + "/standings", "events/" + theirs + "/claims", "events/" + theirs + "/awards")) {
            assertEquals(404, failure("GET", path, null).status, path);
        }
        assertEquals(404, failure("POST", "events/" + theirs + "/status", "{\"status\":\"signups\"}").status);
        assertEquals(404, failure("PUT", "events/" + theirs + "/objectives", "{\"objectives\":[]}").status);
        assertEquals(404, failure("DELETE", "events/" + theirs, null).status);
    }

    @Test
    void aBadIdOrMethodIsRefusedPlainly() {
        assertEquals(405, failure("PATCH", "events/" + create("{\"name\":\"Method test\"}"), null).status);
        assertEquals(405, failure("GET", "events/" + create("{\"name\":\"Method test 2\"}") + "/status", null).status);
        assertTrue(failure("GET", "events/abc", null).status >= 400);
    }

    // ---------- creating and changing ----------

    @Test
    void anEventNeedsAGoodNameAndTheReasonsComeBackAsAList() {
        var error = failure("POST", "events", "{\"name\":\"x\"}");
        assertEquals(400, error.status);
        assertFalse(error.problems.isEmpty());
        assertEquals(400, failure("POST", "events", "{\"name\":\"A fine name\",\"template\":\"nonsense\"}").status);
        assertEquals(400, failure("POST", "events", "{\"name\":\"A fine name\",\"scope\":\"world\"}").status);
        assertEquals(400, failure("POST", "events", "{\"name\":\"A fine name\",\"config\":{\"teamPolicy\":\"chaos\"}}").status);
    }

    @Test
    void aCustomEventStartsEmptyAndAnEventCanBeRenamedAndDeletedWhileADraft() {
        String id = create("{\"name\":\"Plain event\",\"description\":\"hello\"}");
        var detail = call("GET", "events/" + id, null);
        assertEquals("DRAFT", detail.getObject("event").getString("status"));
        assertEquals(0, count(detail.getArray("objectives")));
        assertTrue(detail.getBoolean("editable"));
        call("PUT", "events/" + id, "{\"name\":\"Renamed event\"}");
        assertEquals("Renamed event", call("GET", "events/" + id, null).getObject("event").getString("name"));
        call("DELETE", "events/" + id, null);
        assertEquals(404, failure("GET", "events/" + id, null).status);
    }

    @Test
    void savingSettingsKeepsWhatTheTemplateBuiltTheBoardFrom() {
        String id = create("{\"name\":\"Bingo keep\",\"template\":\"bingo\",\"config\":{\"board\":{\"size\":3}}}");
        call("PUT", "events/" + id, "{\"config\":{\"queue\":\"CLAN_FIRST\",\"board\":{\"size\":7}}}");
        var config = call("GET", "events/" + id, null).getObject("event").getObject("config");
        assertEquals(3, config.getObject("board").getInt("size"));
        assertEquals(9, config.getObject("board").getArray("keys").length());
        assertEquals("CLAN_FIRST", config.getString("queue"));
        assertEquals(9, config.getArray("bonuses").length());
    }

    @Test
    void anEventCantStartWithoutObjectivesAndItsStagesOnlyGoForward() {
        String id = create("{\"name\":\"Stage test\"}");
        assertEquals(409, failure("POST", "events/" + id + "/status", "{\"status\":\"running\"}").status);
        assertEquals(400, failure("POST", "events/" + id + "/status", "{\"status\":\"sideways\"}").status);
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"Kill Zuk\",\"points\":10,\"evidence\":\"either\"}]}");
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        assertEquals(409, failure("POST", "events/" + id + "/status", "{\"status\":\"draft\"}").status);
        assertEquals(409, failure("PUT", "events/" + id + "/objectives", "{\"objectives\":[]}").status, "locked once running");
        call("POST", "events/" + id + "/status", "{\"status\":\"cancelled\"}");
        assertEquals(409, failure("POST", "events/" + id + "/status", "{\"status\":\"running\"}").status);
    }

    @Test
    void objectiveMistakesAreListedAndPrerequisitesCantLoop() {
        String id = create("{\"name\":\"Objective test\"}");
        var error = failure("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"\",\"points\":-5},{\"label\":\"Same\"},{\"label\":\"Same\"}]}");
        assertEquals(400, error.status);
        assertTrue(error.problems.size() >= 2, error.problems.toString());
        var loop = failure("PUT", "events/" + id + "/objectives",
                "{\"objectives\":[{\"key\":\"a\",\"label\":\"A\",\"prerequisites\":[\"b\"]},{\"key\":\"b\",\"label\":\"B\",\"prerequisites\":[\"a\"]}]}");
        assertEquals(400, loop.status);
    }

    // ---------- a whole event, start to payout ----------

    @Test
    void aTeamEventRunsFromSignupsToAPaidPrize() {
        String id = create("{\"name\":\"Team event\",\"scoringMode\":\"team\",\"config\":{\"buyIn\":{\"enabled\":true,\"amount\":1000000,\"showPot\":true}}}");
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"key\":\"zuk\",\"label\":\"Kill Zuk\",\"points\":50,\"evidence\":\"either\"},{\"key\":\"cape\",\"label\":\"Cape\",\"points\":100,\"evidence\":\"proof\",\"prerequisites\":[\"zuk\"]}]}");
        call("POST", "events/" + id + "/teams", "{\"name\":\"Red\"}");
        call("POST", "events/" + id + "/teams", "{\"name\":\"Blue\"}");
        assertEquals(409, failure("POST", "events/" + id + "/teams", "{\"name\":\"Red\"}").status, "a team name is used once");
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        var teams = call("GET", "events/" + id, null).getArray("teams");
        String red = teams.getObject(0).getString("name").equals("Red") ? teams.getObject(0).getString("id") : teams.getObject(1).getString("id");
        call("POST", "events/" + id + "/participants", "{\"userId\":\"101\",\"teamId\":\"" + red + "\"}");
        call("POST", "events/" + id + "/participants", "{\"userId\":\"102\"}");
        assertEquals(409, failure("POST", "events/" + id + "/participants", "{\"userId\":\"101\"}").status, "already in");

        // buy-ins: due, paid, refunded, never backwards
        call("PUT", "events/" + id + "/participants/101/buyin", "{\"status\":\"paid\"}");
        assertEquals(1_000_000L, call("GET", "events/" + id, null).getLong("pot"));
        assertEquals(409, failure("PUT", "events/" + id + "/participants/101/buyin", "{\"status\":\"due\"}").status);

        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        // a player with no team can't score in a team event; a locked objective can't be recorded; then it works
        assertEquals(409, failure("POST", "events/" + id + "/progress", "{\"userId\":\"102\",\"objective\":\"zuk\"}").status);
        assertEquals(409, failure("POST", "events/" + id + "/progress", "{\"userId\":\"101\",\"objective\":\"cape\"}").status);
        var recorded = call("POST", "events/" + id + "/progress", "{\"userId\":\"101\",\"objective\":\"zuk\",\"amount\":1,\"sourceKey\":\"once\"}");
        assertEquals(1, recorded.getInt("completions"));
        assertEquals(409, failure("POST", "events/" + id + "/progress", "{\"userId\":\"101\",\"objective\":\"zuk\",\"amount\":1,\"sourceKey\":\"once\"}").status, "the same source key counts once");

        // proof: the player's claim waits, a denial needs a reason, an approval counts once
        call("POST", "events/" + id + "/claims", "{\"userId\":\"101\",\"objective\":\"cape\",\"amount\":1,\"note\":\"here it is\",\"proofUrl\":\"https://example.com/p.png\"}");
        assertEquals(400, failure("POST", "events/" + id + "/claims", "{\"userId\":\"101\",\"objective\":\"cape\",\"amount\":1,\"note\":\"x\",\"proofUrl\":\"javascript:alert(1)\"}").status);
        var pending = call("GET", "events/" + id + "/claims", null).getArray("pending");
        assertEquals(1, count(pending));
        String claim = pending.getObject(0).getString("id");
        assertEquals(400, failure("POST", "events/" + id + "/claims/" + claim + "/deny", "{\"note\":\"\"}").status);
        call("POST", "events/" + id + "/claims/" + claim + "/approve", "{\"note\":\"nice\"}");
        assertEquals(409, failure("POST", "events/" + id + "/claims/" + claim + "/approve", "{}").status, "decided once");
        var standings = call("GET", "events/" + id + "/standings", null).getArray("standings");
        assertEquals(1, count(standings));
        assertEquals("Red", standings.getObject(0).getString("label"));
        assertEquals(150, standings.getObject(0).getLong("points"));

        // prizes and payout
        call("PUT", "events/" + id + "/prizes", "{\"prizes\":[{\"place\":1,\"placeTo\":1,\"kind\":\"in_game\",\"description\":\"Zaros godsword\",\"amount\":0,\"split\":\"each\"}]}");
        assertEquals(409, failure("POST", "events/" + id + "/awards", "{}").status, "not until scoring");
        call("POST", "events/" + id + "/status", "{\"status\":\"scoring\"}");
        var awards = call("POST", "events/" + id + "/awards", "{}").getArray("awards");
        assertEquals(1, count(awards));
        String award = awards.getObject(0).getString("id");
        assertEquals(count(awards), count(call("POST", "events/" + id + "/awards", "{}").getArray("awards")), "working it out again changes nothing");
        call("POST", "events/" + id + "/awards/" + award + "/status", "{\"status\":\"paid\"}");
        assertEquals(409, failure("POST", "events/" + id + "/awards/" + award + "/status", "{\"status\":\"pending\"}").status);
        var history = call("GET", "events/" + id + "/awards", null).getArray("awards").getObject(0).getArray("history");
        assertEquals(1, count(history));
    }

    // ---------- bingo ----------

    @Test
    void aBingoGetsABoardCanBeRerolledAndHasATileReplaced() {
        String id = create("{\"name\":\"Bingo routes\",\"template\":\"bingo\",\"config\":{\"board\":{\"size\":3,\"seed\":4}}}");
        var first = call("GET", "events/" + id, null);
        assertEquals(9, count(first.getArray("objectives")));
        var keys = first.getObject("event").getObject("config").getObject("board").getArray("keys");
        String middle = keys.getString(4);

        var rerolled = call("POST", "events/" + id + "/bingo", "{\"seed\":99,\"lockedKeys\":[\"" + middle + "\"]}");
        assertEquals(middle, rerolled.getObject("event").getObject("config").getObject("board").getArray("keys").getString(4));

        var replaced = call("POST", "events/" + id + "/bingo", "{\"replace\":{\"number\":1,\"label\":\"Hand in a clue scroll\",\"points\":15}}");
        var config = replaced.getObject("event").getObject("config");
        assertEquals("hand-in-a-clue-scroll", config.getObject("board").getArray("keys").getString(0));
        assertEquals(9, count(replaced.getArray("objectives")));
        var firstTile = replaced.getArray("objectives").getObject(0);
        assertEquals("Hand in a clue scroll", firstTile.getString("label"));
        assertEquals(15, firstTile.getLong("points"));
        // every bonus still lists tiles that exist, and the top row's bonus follows the new tile
        var bonuses = config.getArray("bonuses");
        boolean topRow = false;
        for (int i = 0; i < bonuses.length(); i++) {
            var bonus = bonuses.getObject(i);
            if (bonus.getString("name").equals("Row 1")) topRow = bonus.getArray("keys").getString(0).equals("hand-in-a-clue-scroll");
        }
        assertTrue(topRow, "Row 1 starts with the new tile");

        // a title already on the board, a missing title and a tile that isn't there are refused with a reason
        String other = keys.getString(2);
        assertEquals(400, failure("POST", "events/" + id + "/bingo", "{\"replace\":{\"number\":2,\"label\":\"Hand in a clue scroll\",\"points\":5}}").status);
        assertEquals(400, failure("POST", "events/" + id + "/bingo", "{\"replace\":{\"number\":2,\"label\":\"\",\"points\":5}}").status);
        assertEquals(400, failure("POST", "events/" + id + "/bingo", "{\"replace\":{\"number\":10,\"label\":\"Nope\",\"points\":5}}").status);
        assertNotNull(other);

        // removing a tile through the objectives list is refused while a bonus needs it
        var objectives = replaced.getArray("objectives");
        var shorter = new StringBuilder("{\"objectives\":[");
        for (int i = 1; i < objectives.length(); i++) {
            var o = objectives.getObject(i);
            shorter.append(i > 1 ? "," : "").append("{\"key\":\"").append(o.getString("key")).append("\",\"label\":\"").append(o.getString("label").replace("\"", "\\\"")).append("\",\"points\":").append(o.getLong("points")).append("}");
        }
        shorter.append("]}");
        assertEquals(400, failure("PUT", "events/" + id + "/objectives", shorter.toString()).status);

        // once it is running the board is locked, and the board route answers with what the panel service said
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        assertEquals(409, failure("POST", "events/" + id + "/bingo", "{\"seed\":5}").status);
        assertEquals(409, failure("POST", "events/" + id + "/bingo", "{\"replace\":{\"number\":3,\"label\":\"Another\",\"points\":5}}").status);
        assertEquals("Posted the board.", call("POST", "events/" + id + "/board", "{}").getString("message"));
    }

    @Test
    void aShapedBingoListsItsShapeEnforcesItsOrderAndCanChangeShape() {
        var shapes = call("GET", "events/bingo-layouts", null).getArray("layouts");
        var ids = new ArrayList<String>();
        for (int i = 0; i < shapes.length(); i++) ids.add(shapes.getObject(i).getString("id"));
        assertEquals(List.of("path", "pyramid", "convergence", "tree"), ids);
        var pyramid = shapes.getObject(1);
        assertEquals(15, pyramid.getInt("tiles"));
        assertEquals(5, pyramid.getArray("rows").length());
        assertEquals(2, pyramid.getObject("requires").getArray("1").length());

        // a pyramid of classic tasks opens in order: the apex needs the two tiles beneath it
        String id = create("{\"name\":\"Pyramid night\",\"template\":\"bingo\",\"config\":{\"board\":{\"layout\":\"pyramid\",\"pool\":\"classic\",\"seed\":3}}}");
        var detail = call("GET", "events/" + id, null);
        assertEquals("pyramid", detail.getObject("layout").getString("id"));
        assertEquals("locked", detail.getObject("layout").getString("unlock"));
        assertEquals(15, count(detail.getArray("objectives")));
        var keys = detail.getObject("event").getObject("config").getObject("board").getArray("keys");
        assertEquals(15, keys.length());
        assertEquals(5, detail.getObject("event").getObject("config").getArray("bonuses").length(), "four tiers and the blackout");
        var apex = detail.getArray("objectives").getObject(0);
        assertEquals(2, apex.getArray("prerequisites").length());
        assertEquals(keys.getString(1), apex.getArray("prerequisites").getString(0));
        assertEquals(0, detail.getArray("objectives").getObject(14).getArray("prerequisites").length(), "the base needs nothing");

        // the engine itself refuses progress on a tile that isn't open yet
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        call("POST", "events/" + id + "/participants", "{\"userId\":\"" + ACTOR + "\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        assertEquals(409, failure("POST", "events/" + id + "/progress", "{\"userId\":\"" + ACTOR + "\",\"objective\":\"" + keys.getString(0) + "\",\"amount\":1}").status);
        call("POST", "events/" + id + "/progress", "{\"userId\":\"" + ACTOR + "\",\"objective\":\"" + keys.getString(14) + "\",\"amount\":1}");

        // a draft can re-roll within its shape and switch to another, which leaves no tiles of the old board behind
        String draft = create("{\"name\":\"Switching\",\"template\":\"bingo\",\"config\":{\"board\":{\"layout\":\"pyramid\",\"pool\":\"classic\",\"seed\":1}}}");
        var tree = call("POST", "events/" + draft + "/bingo", "{\"layout\":\"tree\",\"seed\":7}");
        assertEquals("tree", tree.getObject("layout").getString("id"));
        assertEquals(16, count(tree.getArray("objectives")));
        var grid = call("POST", "events/" + draft + "/bingo", "{\"layout\":\"grid\",\"size\":4,\"seed\":7}");
        assertTrue(grid.isNull("layout"));
        assertEquals(16, count(grid.getArray("objectives")));
        assertEquals(400, failure("POST", "events/" + draft + "/bingo", "{\"layout\":\"spiral\"}").status);
    }

    @Test
    void aBingosLookIsChosenAtCreationKeptThroughARerollAndChangedUntilTheEventIsOver() {
        var looks = call("GET", "events/bingo-layouts", null).getArray("looks");
        assertEquals(2, looks.length());
        assertEquals("classic", looks.getObject(0).getString("id"));
        assertEquals("runescape", looks.getObject(1).getString("id"));
        assertFalse(looks.getObject(1).getString("label").isEmpty());

        String id = create("{\"name\":\"Look night\",\"template\":\"bingo\",\"config\":{\"board\":{\"size\":3,\"seed\":4,\"look\":\"runescape\"}}}");
        assertEquals("runescape", lookOf(call("GET", "events/" + id, null)));

        var changed = call("POST", "events/" + id + "/look", "{\"look\":\"classic\"}");
        assertEquals("classic", lookOf(changed));
        assertEquals(9, count(changed.getArray("objectives")), "only the picture changed");
        assertEquals("classic", lookOf(call("POST", "events/" + id + "/bingo", "{\"seed\":8}")), "a re-roll keeps the look");

        assertEquals(400, failure("POST", "events/" + id + "/look", "{\"look\":\"neon\"}").status);
        assertEquals("classic", lookOf(call("GET", "events/" + id, null)), "a refused change leaves it alone");
        assertEquals(400, failure("POST", "events", "{\"name\":\"Bad look\",\"template\":\"bingo\",\"config\":{\"board\":{\"size\":3,\"look\":\"neon\"}}}").status);
        assertNull(lookOf(call("POST", "events/" + id + "/look", "{\"look\":\"\"}")), "an empty look goes back to how the board was first drawn");

        // it is only a picture, so it may change while the event runs, but not once the event is over
        call("POST", "events/" + id + "/look", "{\"look\":\"runescape\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        assertEquals("classic", lookOf(call("POST", "events/" + id + "/look", "{\"look\":\"classic\"}")));
        call("POST", "events/" + id + "/status", "{\"status\":\"scoring\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"done\"}");
        assertEquals(409, failure("POST", "events/" + id + "/look", "{\"look\":\"runescape\"}").status);

        String notBingo = create("{\"name\":\"Not a bingo\"}");
        assertEquals(409, failure("POST", "events/" + notBingo + "/look", "{\"look\":\"classic\"}").status);
    }

    /** The look in an event's settings, or null when it has none. */
    private static String lookOf(DataObject detail) {
        var board = detail.getObject("event").getObject("config").getObject("board");
        return board.hasKey("look") && !board.isNull("look") ? board.getString("look") : null;
    }

    @Test
    void theBingoRoutesRefuseAnEventThatIsntABingo() {
        String id = create("{\"name\":\"Not a bingo\"}");
        assertEquals(409, failure("POST", "events/" + id + "/bingo", "{\"seed\":1}").status);
    }

    @Test
    void everyTemplateCreatesAnEventWithItsOwnObjectives() {
        assertEquals(9, count(call("GET", "events/" + create("{\"name\":\"T bingo\",\"template\":\"bingo\",\"config\":{\"board\":{\"size\":3}}}"), null).getArray("objectives")));
        assertEquals(5, count(call("GET", "events/" + create("{\"name\":\"T hunt\",\"template\":\"scavenger-hunt\",\"config\":{\"steps\":5}}"), null).getArray("objectives")));
        assertEquals(1, count(call("GET", "events/" + create("{\"name\":\"T race\",\"template\":\"competition\",\"config\":{\"skillId\":18}}"), null).getArray("objectives")));
        assertEquals(1, count(call("GET", "events/" + create("{\"name\":\"T boss\",\"template\":\"boss-night\",\"config\":{\"boss\":\"Araxxor\",\"kills\":4}}"), null).getArray("objectives")));
        assertEquals(400, failure("POST", "events", "{\"name\":\"Bad hunt\",\"template\":\"scavenger-hunt\",\"config\":{\"steps\":1}}").status);
        assertEquals(400, failure("POST", "events", "{\"name\":\"Bad race\",\"template\":\"competition\",\"config\":{\"skillId\":77}}").status);
    }

    // ---------- people ----------

    @Test
    void aFullEventPutsTheNextPlayerOnTheWaitlistAndGivesTheirPlaceToTheNextWhenSomeoneLeaves() {
        String id = create("{\"name\":\"Waitlist test\",\"maxParticipants\":2}");
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"Anything\",\"points\":1}]}");
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        for (String user : List.of("201", "202", "203")) call("POST", "events/" + id + "/participants", "{\"userId\":\"" + user + "\"}");
        var players = call("GET", "events/" + id, null).getArray("participants");
        long waiting = 0;
        for (int i = 0; i < players.length(); i++) if (players.getObject(i).getBoolean("waiting")) waiting++;
        assertEquals(1, waiting);
        call("DELETE", "events/" + id + "/participants/201", null);
        players = call("GET", "events/" + id, null).getArray("participants");
        for (int i = 0; i < players.length(); i++) assertFalse(players.getObject(i).getBoolean("waiting"), "the waiting player got the free place");
    }

    @Test
    void twoPeopleJoiningTheLastPlaceAtOnceCantBothGetIt() throws Exception {
        String id = create("{\"name\":\"Last place race\",\"maxParticipants\":1}");
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"Anything\",\"points\":1}]}");
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> joins = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                long user = 300 + i;
                joins.add(() -> service.join(G, Long.parseLong(id), G, user, null, true, null).waiting());
            }
            long active = 0;
            for (Future<Boolean> f : pool.invokeAll(joins)) if (!f.get()) active++;
            assertEquals(1, active, "exactly one place was given out");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTurnedAwaySignupIsListedForStaffAndClearedWhenTheyAreAdded() {
        String id = create("{\"name\":\"Refusal test\"}");
        repository.recordRefusal(Long.parseLong(id), 401, "This event isn't open to players from other servers.");
        var refused = call("GET", "events/" + id, null).getArray("refused");
        assertEquals(1, count(refused));
        assertEquals("This event isn't open to players from other servers.", refused.getObject(0).getString("reason"));
        assertFalse(repository.recordRefusal(Long.parseLong(id), 401, "This event isn't open to players from other servers."), "the same reason is only news once");
        assertTrue(repository.recordRefusal(Long.parseLong(id), 401, "A different reason."));
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"Anything\",\"points\":1}]}");
        call("POST", "events/" + id + "/status", "{\"status\":\"signups\"}");
        call("POST", "events/" + id + "/participants", "{\"userId\":\"401\"}");
        assertEquals(0, count(call("GET", "events/" + id, null).getArray("refused")));
    }

    // ---------- organisers ----------

    private static final String PERSON = "123456789012345678";

    @Test
    void organisersAreNamedByIdOrLinkedRsnListedAndRemoved() {
        String id = create("{\"name\":\"Run by others\"}");
        Mockito.when(links.getAllLinks(G)).thenReturn(List.of(new com.younglings.bot.runescape.PlayerLink(1, G, 555_000_000_000_000_001L, "Axley", "x", null, null)));

        var added = call("POST", "events/" + id + "/organisers", "{\"add\":[\"" + PERSON + "\",\"axley\"]}").getArray("organisers");
        assertEquals(2, count(added));
        assertEquals(PERSON, added.getObject(0).getString("userId"));
        assertEquals("555000000000000001", added.getObject(1).getString("userId"));

        var left = call("POST", "events/" + id + "/organisers", "{\"remove\":[\"" + PERSON + "\"]}").getArray("organisers");
        assertEquals(1, count(left));
        assertEquals(List.of(555_000_000_000_000_001L), service.organisers(G, Long.parseLong(id)));
    }

    @Test
    void aSettingsSaveDoesNotWipeTheOrganisers() {
        String id = create("{\"name\":\"Keep my organisers\"}");
        call("POST", "events/" + id + "/organisers", "{\"add\":[\"" + PERSON + "\"]}");
        call("PUT", "events/" + id, "{\"name\":\"Renamed\",\"config\":{\"queue\":\"CLAN_FIRST\"}}");
        call("PUT", "events/" + id, "{\"config\":{\"organisers\":[\"999999999999999999\"]}}");
        assertEquals(List.of(Long.parseLong(PERSON)), service.organisers(G, Long.parseLong(id)), "organisers only change through their own call");
    }

    @Test
    void anUnknownNameOrTooManyOrganisersAreRefused() {
        String id = create("{\"name\":\"Limits\"}");
        Mockito.when(links.getAllLinks(G)).thenReturn(List.of());
        assertEquals(400, failure("POST", "events/" + id + "/organisers", "{\"add\":[\"nobody\"]}").status);
        StringBuilder many = new StringBuilder("{\"add\":[");
        for (int i = 0; i < 11; i++) many.append(i == 0 ? "" : ",").append("\"").append(100_000_000_000_000_000L + i).append("\"");
        assertEquals(400, failure("POST", "events/" + id + "/organisers", many + "]}").status);
        assertEquals(0, service.organisers(G, Long.parseLong(id)).size(), "a refused change changes nothing");
    }

    @Test
    void onlyAnOrganiserOrStaffMayRunTheEvent() {
        String id = create("{\"name\":\"Who may run it\"}");
        call("POST", "events/" + id + "/organisers", "{\"add\":[\"" + PERSON + "\"]}");
        var event = service.event(G, Long.parseLong(id));
        assertTrue(service.mayOrganise(event, Long.parseLong(PERSON), false));
        assertFalse(service.mayOrganise(event, 42L, false));
        assertTrue(service.mayOrganise(event, 42L, true), "staff may run any event");
    }

    // ---------- tiles, archive and delete ----------

    private String finishedEvent(String name) {
        String id = create("{\"name\":\"" + name + "\"}");
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"Anything\",\"points\":1}]}");
        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"scoring\"}");
        call("POST", "events/" + id + "/status", "{\"status\":\"done\"}");
        return id;
    }

    private static boolean listed(DataObject list, String id, boolean archived) {
        var events = list.getArray("events");
        for (int i = 0; i < events.length(); i++) {
            if (events.getObject(i).getString("id").equals(id)) return events.getObject(i).getBoolean("archived") == archived;
        }
        return false;
    }

    @Test
    void aBingoDetailNamesTheBossItemAndPictureOfEveryTile() {
        String id = create("{\"name\":\"Pictured bingo\",\"template\":\"bingo\",\"config\":{\"board\":{\"size\":3}}}");
        var tiles = call("GET", "events/" + id, null).getArray("tiles");
        assertEquals(9, count(tiles));
        for (int i = 0; i < tiles.length(); i++) assertFalse(tiles.getObject(i).getString("item").isEmpty());
        assertEquals(0, count(call("GET", "events/" + create("{\"name\":\"Plain\"}"), null).getArray("tiles")), "only a bingo has tiles");
    }

    @Test
    void theBingoCatalogListsBossesWithTheirLoot() {
        var bosses = call("GET", "events/bingo-catalog", null).getArray("bosses");
        assertTrue(bosses.length() > 30);
        assertTrue(bosses.getObject(0).getArray("items").length() > 0);
    }

    @Test
    void aFinishedEventCanBeArchivedAndBroughtBack() {
        String id = finishedEvent("Archive me");
        assertTrue(listed(call("GET", "events", null), id, false));
        assertTrue(listed(call("POST", "events/" + id + "/archive", "{\"archived\":true}"), id, true));
        assertTrue(listed(call("POST", "events/" + id + "/archive", "{\"archived\":false}"), id, false));
    }

    @Test
    void anEventThatIsStillGoingCannotBeArchivedOrDeleted() {
        String id = create("{\"name\":\"Still going\"}");
        call("PUT", "events/" + id + "/objectives", "{\"objectives\":[{\"label\":\"Anything\",\"points\":1}]}");
        call("POST", "events/" + id + "/status", "{\"status\":\"running\"}");
        assertEquals(409, failure("POST", "events/" + id + "/archive", "{\"archived\":true}").status);
        assertEquals(409, failure("DELETE", "events/" + id, null).status);
        call("POST", "events/" + id + "/status", "{\"status\":\"cancelled\"}");
        call("DELETE", "events/" + id, null);
        assertEquals(404, failure("GET", "events/" + id, null).status);
    }

    @Test
    void aFinishedEventAndEverythingInItCanBeDeleted() {
        String id = finishedEvent("Delete me");
        call("DELETE", "events/" + id, null);
        assertEquals(404, failure("GET", "events/" + id, null).status);
        assertEquals(0, call("GET", "events", null).getArray("events").length());
    }

    @Test
    void archivingAllFinishedEventsLeavesTheOthersAlone() {
        String one = finishedEvent("Done one");
        String two = finishedEvent("Done two");
        String open = create("{\"name\":\"Open one\"}");
        var after = call("POST", "events/archive-finished", null);
        assertEquals(2, after.getInt("archivedCount"));
        assertTrue(listed(after, one, true) && listed(after, two, true));
        assertTrue(listed(after, open, false));
        assertEquals(0, call("POST", "events/archive-finished", null).getInt("archivedCount"));
    }
}
