package com.runeclans.bot.events;

import com.runeclans.bot.events.EventBlueprintRepository.Blueprint;
import com.runeclans.bot.events.EventDraftRepository.Draft;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setups end to end over the developer's local Postgres (skipped without one): the standard and quick setups, drafts that are finished later, and saved
 * templates, all ending in the engine's own rules. Everything is made in made-up servers and removed afterwards.
 */
class EventSetupTest {
    private static final long G = 999_000_000_000_000_041L;
    private static final long OTHER = 999_000_000_000_000_042L;
    private static final long ACTOR = 9L;
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private EventSetup setup;

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
        service = new EventService(repository, templates, kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        setup = new EventSetup(service, templates, new EventDraftRepository(supplier), new EventBlueprintRepository(supplier), new EventDefaultsRepository(supplier),
                new BingoPools(org.mockito.Mockito.mock(BingoTileRepository.class)));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String table : new String[]{"younglings.event", "younglings.event_draft", "younglings.event_blueprint"}) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE guild_id IN (?, ?)")) {
                    s.setLong(1, G);
                    s.setLong(2, OTHER);
                    s.executeUpdate();
                }
            }
        }
    }

    private Event event(long id) {
        return service.event(G, id);
    }

    private static DataObject over(String json) {
        return DataObject.fromJson(json);
    }

    // ---------- the standard and quick setups ----------

    @Test
    void theStandardSetupMakesADraftEventForEveryKindNeedingNothingButABossNight() {
        for (String kind : new String[]{"custom", "bingo", "scavenger-hunt", "competition"}) {
            long id = setup.createStandard(G, ACTOR, kind, null, NOW);
            Event e = event(id);
            assertEquals(Status.DRAFT, e.status(), kind);
            assertEquals(kind, e.template());
            assertEquals(EventRules.Scope.PRIVATE, e.scope());
            assertEquals(EventRules.ScoringMode.INDIVIDUAL, e.scoringMode());
            assertEquals(Instant.parse("2026-10-18T10:00:00Z"), e.startsAt());
            assertEquals(Instant.parse("2026-10-25T10:00:00Z"), e.endsAt());
            assertTrue(e.name().endsWith(" · 18 Oct"), e.name());
            EventSettings settings = EventSettings.parse(DataObject.fromJson(e.config()));
            assertEquals(EventPrep.Mode.STANDARD, settings.setupMode(), kind + " follows the setup steps");
            assertFalse(settings.verification().required());
        }
        Event bingo = event(setup.createStandard(G, ACTOR, "bingo", null, NOW));
        assertEquals(25, service.objectives(bingo.id()).size(), "a 5 by 5 board");
        assertEquals("runescape", DataObject.fromJson(bingo.config()).getObject("board").getString("look"));
    }

    @Test
    void aBossNightNeedsItsBossAndTheRefusalSaysSo() {
        EventProblem refused = assertThrows(EventProblem.class, () -> setup.createStandard(G, ACTOR, "boss-night", null, NOW));
        assertEquals(400, refused.status());
        assertTrue(refused.problems().stream().anyMatch(p -> p.toLowerCase().contains("boss")) || refused.getMessage().toLowerCase().contains("boss"), refused.problems().toString());
        long id = setup.createStandard(G, ACTOR, "boss-night", over("{\"config.boss\":\"Araxxor\"}"), NOW);
        assertEquals("Kill Araxxor", service.objectives(id).get(0).label());
    }

    @Test
    void theQuickSetupLaysAFewAnswersOverTheStandardOnes() {
        long id = setup.createStandard(G, ACTOR, "bingo", over("{\"name\":\"Friday bingo\",\"config.board.layout\":\"pyramid\",\"startsAt\":\"2026-11-01T18:00:00Z\",\"endsAt\":\"2026-11-03T18:00:00Z\"}"), NOW);
        Event e = event(id);
        assertEquals("Friday bingo", e.name());
        assertEquals(15, service.objectives(id).size(), "a pyramid has fifteen tiles");
        DataObject board = DataObject.fromJson(e.config()).getObject("board");
        assertEquals("pyramid", board.getString("layout"));
        assertFalse(board.hasKey("unlock") && board.getString("unlock").equals("auto"), "automatic is left for the template to decide");
        assertEquals(Instant.parse("2026-11-01T18:00:00Z"), e.startsAt());
        assertEquals(Instant.parse("2026-11-03T18:00:00Z"), e.endsAt());
    }

    @Test
    void thatASetupIsQuickDoesntLetItBreakTheEnginesRules() {
        EventProblem backwards = assertThrows(EventProblem.class, () -> setup.createStandard(G, ACTOR, "custom", over("{\"startsAt\":\"2026-11-03T18:00:00Z\",\"endsAt\":\"2026-11-01T18:00:00Z\"}"), NOW));
        assertEquals(400, backwards.status());
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.createStandard(G, ACTOR, "custom", over("{\"name\":\"x\"}"), NOW)).status(), "a short name is the engine's refusal too");
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.createStandard(G, ACTOR, "spiral", null, NOW)).status());
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.createStandard(G, ACTOR, "custom", over("{\"not a path!\":1}"), NOW)).status());
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.createStandard(G, ACTOR, "custom", over("{\"startsAt\":\"tomorrow\"}"), NOW)).status());
        assertEquals(0, repository.events(G, List.of(), 100).size(), "nothing was left behind");
    }

    @Test
    void prizesAreSavedWithTheEventAndBadOnesStopItBeingMade() {
        long id = setup.create(G, ACTOR, EventOptions.standard(new CustomTemplate(), NOW).put("prizes", DataObject.fromJson("{\"p\":[{\"place\":1,\"kind\":\"IN_GAME\",\"description\":\"Zaros godsword\",\"split\":\"EACH\"},"
                + "{\"place\":2,\"placeTo\":3,\"kind\":\"CLAN_POINTS\",\"amount\":500}]}").getArray("p")));
        var prizes = service.prizes(id);
        assertEquals(2, prizes.size());
        assertEquals("Zaros godsword", prizes.get(0).description());
        assertEquals(3, prizes.get(1).placeTo());

        DataObject pot = EventOptions.standard(new CustomTemplate(), NOW).put("prizes", DataObject.fromJson("{\"p\":[{\"place\":1,\"kind\":\"IN_GAME\",\"description\":\"Half the pot\",\"percentOfPot\":5000}]}").getArray("p"));
        EventProblem refused = assertThrows(EventProblem.class, () -> setup.create(G, ACTOR, pot));
        assertTrue(refused.problems().stream().anyMatch(p -> p.contains("share of the pot")), refused.problems().toString());
        assertEquals(1, repository.events(G, List.of(), 100).size(), "the second event wasn't made");
        // with a buy-in the same prize is fine
        EventOptions.set(pot, "config.buyIn.enabled", true);
        EventOptions.set(pot, "config.buyIn.amount", 1_000_000);
        long withBuyIn = setup.create(G, ACTOR, pot);
        assertEquals(5000, service.prizes(withBuyIn).get(0).percentOfPot());
    }

    @Test
    void whatIsMissingIsSaidInWordsBeforeAnythingIsMade() {
        DataObject answers = EventOptions.standard(new CustomTemplate(), NOW);
        answers.put("name", "");
        EventOptions.set(answers, "config.buyIn.enabled", true);
        EventProblem refused = assertThrows(EventProblem.class, () -> setup.create(G, ACTOR, answers));
        assertEquals(400, refused.status());
        assertEquals(2, refused.problems().size(), refused.problems().toString());
        assertEquals(0, repository.events(G, List.of(), 100).size());
    }

    // ---------- drafts ----------

    @Test
    void aDraftKeepsTheAnswersAndTheStepAndIsFinishedIntoAnEvent() {
        Draft started = setup.startDraft(G, ACTOR, "scavenger-hunt", "guided", NOW);
        assertEquals("GUIDED", started.stream());
        assertEquals("BASICS", started.step());
        assertEquals("Scavenger hunt · 18 Oct", started.title());
        DataObject answers = DataObject.fromJson(started.answers());
        answers.put("name", "Clue week");
        EventOptions.set(answers, "config.steps", 5);

        Draft saved = setup.saveDraft(G, started.id(), answers, "rules");
        assertEquals("RULES", saved.step());
        assertEquals("Clue week", saved.title());
        assertEquals(1, setup.drafts(G).size());
        assertEquals(0, setup.drafts(OTHER).size(), "another server doesn't see it");
        assertEquals(404, assertThrows(EventProblem.class, () -> setup.draft(OTHER, started.id())).status());

        long eventId = setup.createFromDraft(G, ACTOR, started.id());
        assertEquals("Clue week", event(eventId).name());
        assertEquals(5, service.objectives(eventId).size());
        assertEquals(0, setup.drafts(G).size(), "finishing it removes the draft");
        assertEquals(404, assertThrows(EventProblem.class, () -> setup.createFromDraft(G, ACTOR, started.id())).status());
    }

    @Test
    void aDraftThatCantBeFinishedStaysSoNothingIsLost() {
        Draft draft = setup.startDraft(G, ACTOR, "boss-night", "guided", NOW);
        EventProblem refused = assertThrows(EventProblem.class, () -> setup.createFromDraft(G, ACTOR, draft.id()));
        assertEquals(400, refused.status());
        assertEquals(1, setup.drafts(G).size());
        assertEquals(0, repository.events(G, List.of(), 100).size());
    }

    @Test
    void aDraftsKindCantChangeAndItsStepHasToBeARealOne() {
        Draft draft = setup.startDraft(G, ACTOR, "competition", null, NOW);
        DataObject answers = DataObject.fromJson(draft.answers());
        answers.put("template", "bingo");
        Draft saved = setup.saveDraft(G, draft.id(), answers, null);
        assertEquals("competition", saved.template());
        assertEquals("competition", DataObject.fromJson(saved.answers()).getString("template"));
        assertEquals("BASICS", saved.step(), "no step given: it stays where it was");
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.saveDraft(G, draft.id(), answers, "NOPE")).status());
        assertEquals("REVIEW", setup.saveDraft(G, draft.id(), answers, "review").step());
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.startDraft(G, ACTOR, "spiral", null, NOW)).status());
        setup.deleteDraft(G, draft.id());
        assertEquals(404, assertThrows(EventProblem.class, () -> setup.deleteDraft(G, draft.id())).status());
    }

    @Test
    void aServerCanKeepOnlySomeDrafts() {
        for (int i = 0; i < EventSetup.MAX_DRAFTS; i++) setup.startDraft(G, ACTOR, "custom", null, NOW);
        assertEquals(409, assertThrows(EventProblem.class, () -> setup.startDraft(G, ACTOR, "custom", null, NOW)).status());
        setup.startDraft(OTHER, ACTOR, "custom", null, NOW); // another server has its own room
    }

    // ---------- saved templates ----------

    @Test
    void aSavedTemplateKeepsTheSetupAndMakesSimilarEventsWithTheirOwnBoards() {
        DataObject answers = EventOptions.standard(new BingoTemplate(), NOW);
        answers.put("name", "Weekly bingo").put("scoringMode", "TEAM").put("maxParticipants", 24);
        EventOptions.set(answers, "config.buyIn.enabled", true);
        EventOptions.set(answers, "config.buyIn.amount", 1_000_000);
        EventOptions.set(answers, "config.board.look", "classic");
        EventOptions.set(answers, "config.board.size", 4);
        answers.put("prizes", DataObject.fromJson("{\"p\":[{\"place\":1,\"kind\":\"IN_GAME\",\"description\":\"Most of the pot\",\"percentOfPot\":6000,\"split\":\"EQUAL\"}]}").getArray("p"));
        long original = setup.create(G, ACTOR, answers);
        service.changeOrganisers(G, original, List.of(111L), List.of());

        Blueprint saved = setup.saveTemplateFromEvent(G, ACTOR, original, "  Team bingo  ", "Four by four with a pot");
        assertEquals("Team bingo", saved.name());
        assertEquals("bingo", saved.template());
        DataObject body = DataObject.fromJson(saved.body());
        assertEquals("TEAM", body.getString("scoringMode"));
        assertEquals(24, body.getInt("maxParticipants"));
        assertNull(EventOptions.get(body, "config.board.keys"), "the tiles belong to one event");
        assertNull(EventOptions.get(body, "config.board.seed"));
        assertNull(EventOptions.get(body, "config.bonuses"));
        assertNull(EventOptions.get(body, "config.organisers"), "who runs an event is that event's business");
        assertEquals("classic", EventOptions.get(body, "config.board.look"));
        assertEquals(4, ((Number) EventOptions.get(body, "config.board.size")).intValue());
        assertEquals(7, DataObject.fromJson(saved.timing()).getInt("days"));

        long copy = setup.createFromTemplate(G, ACTOR, saved.id(), null, null, NOW);
        Event e = event(copy);
        assertEquals("Team bingo · 18 Oct", e.name());
        assertNotEquals(original, copy);
        assertEquals(EventRules.ScoringMode.TEAM, e.scoringMode());
        assertEquals(24, e.maxParticipants());
        assertEquals(16, service.objectives(copy).size(), "its own 4 by 4 board");
        assertEquals(1, service.prizes(copy).size());
        assertEquals(6000, service.prizes(copy).get(0).percentOfPot());
        assertTrue(EventSettings.parse(DataObject.fromJson(e.config())).buyIn().enabled());
        assertTrue(EventSettings.parse(DataObject.fromJson(e.config())).organisers().isEmpty());
        long named = setup.createFromTemplate(G, ACTOR, saved.id(), "Halloween bingo", Instant.parse("2026-10-31T18:00:00Z"), NOW);
        assertEquals("Halloween bingo", event(named).name());
        assertEquals(Instant.parse("2026-10-31T18:00:00Z"), event(named).startsAt());
        assertEquals(Instant.parse("2026-11-07T18:00:00Z"), event(named).endsAt(), "it runs as long as the template did");
    }

    @Test
    void aSavedTemplateRemembersHowLongItRanAndHowSoonSignupsClosedBeforeItStarted() {
        DataObject answers = EventOptions.standard(new CustomTemplate(), NOW);
        answers.put("startsAt", "2026-11-01T18:00:00Z").put("endsAt", "2026-11-04T18:00:00Z");
        EventOptions.set(answers, "config.signupClosesAt", "2026-11-01T16:00:00Z");
        long id = setup.create(G, ACTOR, answers);
        Blueprint saved = setup.saveTemplateFromEvent(G, ACTOR, id, "Three days", "");
        DataObject timing = DataObject.fromJson(saved.timing());
        assertEquals(3, timing.getInt("days"));
        assertEquals(2, timing.getInt("signupClosesBeforeStartHours"));
        assertNull(EventOptions.get(DataObject.fromJson(saved.body()), "config.signupClosesAt"), "the date itself isn't kept");

        long copy = setup.createFromTemplate(G, ACTOR, saved.id(), "Next time", Instant.parse("2026-12-01T20:00:00Z"), NOW);
        Event e = event(copy);
        assertEquals(Instant.parse("2026-12-04T20:00:00Z"), e.endsAt());
        assertEquals(Instant.parse("2026-12-01T18:00:00Z"), EventSettings.parse(DataObject.fromJson(e.config())).signupClosesAt());
    }

    @Test
    void wholeSpanIsSkillOfTheWeeksOwnAndNeverSetFromTheSetup() {
        DataObject answers = EventOptions.standard(new CompetitionTemplate(), NOW);
        EventOptions.set(answers, "config.skillId", 9);
        EventOptions.set(answers, "config.wholeSpan", true);
        DataObject config = DataObject.fromJson(event(setup.create(G, ACTOR, answers)).config());
        assertFalse(config.hasKey("wholeSpan"));
        assertEquals(9, config.getInt("skillId"));
    }

    @Test
    void savedTemplatesHaveUniqueNamesCanBeRenamedAndDeleted() {
        long id = setup.createStandard(G, ACTOR, "custom", null, NOW);
        Blueprint first = setup.saveTemplateFromEvent(G, ACTOR, id, "Clan night", "");
        assertEquals(409, assertThrows(EventProblem.class, () -> setup.saveTemplateFromEvent(G, ACTOR, id, "clan NIGHT", "")).status(), "names compare without case");
        Blueprint second = setup.saveTemplateFromEvent(G, ACTOR, id, "Second", "Another one");
        assertEquals(2, setup.savedTemplates(G).size());
        assertEquals(0, setup.savedTemplates(OTHER).size());
        assertEquals(409, assertThrows(EventProblem.class, () -> setup.renameTemplate(G, second.id(), "Clan Night", "")).status());
        assertEquals("Renamed", setup.renameTemplate(G, second.id(), "Renamed", "New words").name());
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.saveTemplateFromEvent(G, ACTOR, id, "x", "")).status(), "too short");
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.saveTemplateFromEvent(G, ACTOR, id, "Fine name", "x".repeat(EventSetup.MAX_TEMPLATE_DESCRIPTION + 1))).status());
        assertEquals(404, assertThrows(EventProblem.class, () -> setup.saveTemplateFromEvent(G, ACTOR, 999_999_999L, "Ghost", "")).status(), "no such event");
        assertEquals(404, assertThrows(EventProblem.class, () -> setup.createFromTemplate(OTHER, ACTOR, first.id(), null, null, NOW)).status(), "another server's template");
        setup.deleteTemplate(G, first.id());
        assertEquals(1, setup.savedTemplates(G).size());
        assertEquals(404, assertThrows(EventProblem.class, () -> setup.deleteTemplate(G, first.id())).status());
    }

    @Test
    void anEventMadeFromATemplateGoesThroughTheSameChecksAsAnyOther() {
        long id = setup.createStandard(G, ACTOR, "boss-night", over("{\"config.boss\":\"Araxxor\"}"), NOW);
        Blueprint saved = setup.saveTemplateFromEvent(G, ACTOR, id, "Boss nights", "");
        assertEquals("Kill Araxxor", service.objectives(setup.createFromTemplate(G, ACTOR, saved.id(), "Araxxor again", null, NOW)).get(0).label());
        assertEquals(400, assertThrows(EventProblem.class, () -> setup.createFromTemplate(G, ACTOR, saved.id(), "x", null, NOW)).status(), "a name that is too short is still refused");
    }
}
