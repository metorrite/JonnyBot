package com.younglings.bot.events;

import com.younglings.bot.events.EventOptions.Missing;
import com.younglings.bot.events.EventOptions.Option;
import com.younglings.bot.events.EventOptions.Step;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventService.EventInput;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventOptionsTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final List<EventTemplate> TEMPLATES = List.of(new CustomTemplate(), new BingoTemplate(), new ScavengerHuntTemplate(), new CompetitionTemplate(), new BossNightTemplate());

    private static EventTemplate template(String key) {
        return TEMPLATES.stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> paths(List<Missing> missing) {
        return missing.stream().map(Missing::path).toList();
    }

    // ---------- standard answers ----------

    @Test
    void theStandardAnswersStartADayFromNowAndRunAWeekWithAGeneratedName() {
        DataObject standard = EventOptions.standard(template("bingo"), NOW);
        assertEquals("2026-10-18T10:00:00Z", standard.getString("startsAt"));
        assertEquals("2026-10-25T10:00:00Z", standard.getString("endsAt"));
        assertEquals("Bingo · 18 Oct", standard.getString("name"));
        assertEquals("bingo", standard.getString("template"));
        assertEquals("PRIVATE", standard.getString("scope"));
        assertEquals("INDIVIDUAL", standard.getString("scoringMode"));
        assertEquals(0, standard.getInt("maxParticipants"));
        assertEquals("everyone", EventOptions.get(standard, "config.teamPolicy"));
        assertEquals("FIRST_COME", EventOptions.get(standard, "config.queue"));
        assertEquals("STANDARD", EventOptions.get(standard, "config.setup.mode"), "a standard event follows the setup steps");
        assertEquals(false, EventOptions.get(standard, "config.verification.required"));
        assertEquals(false, EventOptions.get(standard, "config.buyIn.enabled"));
    }

    @Test
    void eachKindsStandardAnswersAreWhatItsTemplateAsksFor() {
        DataObject bingo = EventOptions.standard(template("bingo"), NOW);
        assertEquals("grid", EventOptions.get(bingo, "config.board.layout"));
        assertEquals(5, ((Number) EventOptions.get(bingo, "config.board.size")).intValue());
        assertEquals("drops", EventOptions.get(bingo, "config.board.pool"));
        assertEquals(BingoLooks.DEFAULT_FOR_NEW, EventOptions.get(bingo, "config.board.look"));
        assertEquals(50, ((Number) EventOptions.get(bingo, "config.lineBonus")).intValue());
        assertEquals(250, ((Number) EventOptions.get(bingo, "config.blackoutBonus")).intValue());

        DataObject hunt = EventOptions.standard(template("scavenger-hunt"), NOW);
        assertEquals(8, ((Number) EventOptions.get(hunt, "config.steps")).intValue());
        assertEquals(25, ((Number) EventOptions.get(hunt, "config.stepPoints")).intValue());
        assertEquals(false, EventOptions.get(hunt, "config.ordered"));
        assertEquals(100, ((Number) EventOptions.get(hunt, "config.finishBonus")).intValue());

        DataObject competition = EventOptions.standard(template("competition"), NOW);
        assertEquals(CompetitionTemplate.OVERALL, ((Number) EventOptions.get(competition, "config.skillId")).intValue());
        assertEquals(10_000, ((Number) EventOptions.get(competition, "config.xpPerPoint")).intValue());

        DataObject boss = EventOptions.standard(template("boss-night"), NOW);
        assertEquals(10, ((Number) EventOptions.get(boss, "config.kills")).intValue());
        assertEquals(10, ((Number) EventOptions.get(boss, "config.pointsPerKill")).intValue());
        assertNull(EventOptions.get(boss, "config.boss"), "no standard boss: it has to be asked for");
    }

    @Test
    void everyStandardEventExceptOneNeedingABossCanBeCreatedWithNothingAdded() {
        for (EventTemplate t : TEMPLATES) {
            DataObject answers = EventOptions.normalize(EventOptions.standard(t, NOW));
            List<Missing> missing = EventOptions.missing(EventOptions.of(t), answers);
            if (t.key().equals(BossNightTemplate.KEY)) {
                assertEquals(List.of("config.boss"), paths(missing));
                answers = EventOptions.normalize(withBoss(answers, "Araxxor"));
            } else {
                assertEquals(List.of(), paths(missing), t.key() + " needs nothing more");
            }
            EventInput in = EventOptions.toInput(answers);
            assertEquals(List.of(), EventRules.eventProblems(in.name(), in.description(), in.maxParticipants(), in.startsAt(), in.endsAt()), t.key());
            assertEquals(List.of(), t.configProblems(in.config()), t.key());
            assertEquals(List.of(), EventSettings.problems(in.config()), t.key());
            assertEquals(t.key(), in.template());
            assertEquals(ScoringMode.INDIVIDUAL, in.scoringMode());
            // and the template can build what it generates from them
            DataObject prepared = t.prepare(in.config());
            assertEquals(List.of(), EventBonuses.problems(prepared, t.starterObjectives(prepared).stream().map(EventRules.ObjectiveSpec::key).collect(java.util.stream.Collectors.toSet())), t.key());
        }
    }

    private static DataObject withBoss(DataObject answers, String boss) {
        DataObject copy = DataObject.fromJson(answers.toString());
        EventOptions.set(copy, "config.boss", boss);
        return copy;
    }

    // ---------- what is missing ----------

    @Test
    void aNameIsAlwaysRequiredAndAShortOneIsMissingToo() {
        EventTemplate custom = template("custom");
        DataObject answers = EventOptions.standard(custom, NOW);
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(custom), answers)));
        EventOptions.set(answers, "name", "   ");
        assertEquals(List.of("name"), paths(EventOptions.missing(EventOptions.of(custom), answers)));
        EventOptions.set(answers, "name", null);
        assertEquals(List.of("name"), paths(EventOptions.missing(EventOptions.of(custom), answers)));
    }

    @Test
    void aBossNightNeedsItsBossAndTheMessageSaysSo() {
        EventTemplate boss = template("boss-night");
        DataObject answers = EventOptions.standard(boss, NOW);
        List<Missing> missing = EventOptions.missing(EventOptions.of(boss), answers);
        assertEquals(List.of("config.boss"), paths(missing));
        assertEquals(Step.RULES, missing.get(0).step());
        EventOptions.set(answers, "config.boss", "Araxxor");
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(boss), answers)));
    }

    @Test
    void aBuyInNeedsItsAmountOnlyWhileItIsSwitchedOn() {
        EventTemplate custom = template("custom");
        DataObject answers = EventOptions.standard(custom, NOW);
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(custom), answers)), "off: nothing to ask");
        EventOptions.set(answers, "config.buyIn.enabled", true);
        assertEquals(List.of("config.buyIn.amount"), paths(EventOptions.missing(EventOptions.of(custom), answers)), "on with the amount still 0");
        EventOptions.set(answers, "config.buyIn.amount", 5_000_000);
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(custom), answers)));
        EventOptions.set(answers, "config.buyIn.amount", "2500000");
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(custom), answers)), "a number typed as text counts");
        EventOptions.set(answers, "config.buyIn.enabled", false);
        EventOptions.set(answers, "config.buyIn.amount", 0);
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(custom), answers)), "switched off again: not asked");
    }

    @Test
    void aSignupNeedsBothChannelsOrNeither() {
        EventTemplate custom = template("custom");
        DataObject answers = EventOptions.standard(custom, NOW);
        EventOptions.set(answers, "config.signupChannelId", "123456789012345678");
        assertEquals(List.of("config.signupAdminChannelId"), paths(EventOptions.missing(EventOptions.of(custom), answers)));
        EventOptions.set(answers, "config.signupAdminChannelId", "223456789012345678");
        assertEquals(List.of(), paths(EventOptions.missing(EventOptions.of(custom), answers)));
        EventOptions.set(answers, "config.signupChannelId", "");
        assertEquals(List.of("config.signupChannelId"), paths(EventOptions.missing(EventOptions.of(custom), answers)));
    }

    @Test
    void missingAnswersComeInTheOrderOfTheGuidedSetup() {
        EventTemplate boss = template("boss-night");
        DataObject answers = EventOptions.standard(boss, NOW);
        EventOptions.set(answers, "name", "");
        EventOptions.set(answers, "config.buyIn.enabled", true);
        assertEquals(List.of("name", "config.boss", "config.buyIn.amount"), paths(EventOptions.missing(EventOptions.of(boss), answers)));
    }

    // ---------- cleaning the answers up ----------

    @Test
    void aGridHasASizeAndNoShapeAndAShapeHasNoSize() {
        DataObject grid = DataObject.fromJson("{\"template\":\"bingo\",\"config\":{\"board\":{\"layout\":\"grid\",\"size\":4,\"unlock\":\"locked\",\"pool\":\"drops\"}}}");
        DataObject cleaned = EventOptions.normalize(grid);
        assertNull(EventOptions.get(cleaned, "config.board.layout"));
        assertNull(EventOptions.get(cleaned, "config.board.unlock"), "a grid has no tile order");
        assertEquals(4, ((Number) EventOptions.get(cleaned, "config.board.size")).intValue());
        assertEquals("grid", EventOptions.get(grid, "config.board.layout"), "the answers handed in are left alone");

        DataObject shape = DataObject.fromJson("{\"template\":\"bingo\",\"config\":{\"board\":{\"layout\":\"pyramid\",\"size\":5,\"unlock\":\"auto\",\"look\":\"classic\"}}}");
        DataObject shaped = EventOptions.normalize(shape);
        assertEquals("pyramid", EventOptions.get(shaped, "config.board.layout"));
        assertNull(EventOptions.get(shaped, "config.board.size"));
        assertNull(EventOptions.get(shaped, "config.board.unlock"), "automatic is left unsaid");
        assertEquals("classic", EventOptions.get(shaped, "config.board.look"));
        assertEquals("locked", EventOptions.get(EventOptions.normalize(DataObject.fromJson("{\"config\":{\"board\":{\"layout\":\"tree\",\"unlock\":\"locked\"}}}")), "config.board.unlock"));
    }

    @Test
    void blankThingsAreDroppedAndTheBuyInIsOnlyKeptWhenOn() {
        DataObject a = DataObject.fromJson("{\"name\":\"  Friday  \",\"scope\":\"private\",\"scoringMode\":\"team\",\"startsAt\":\"\",\"config\":{\"signupChannelId\":\"\",\"reviewChannelId\":\"123\","
                + "\"signupClosesAt\":\" \",\"buyIn\":{\"enabled\":false,\"amount\":0,\"showPot\":true},\"queue\":null}}");
        DataObject cleaned = EventOptions.normalize(a);
        assertEquals("Friday", cleaned.getString("name"));
        assertEquals("PRIVATE", cleaned.getString("scope"));
        assertEquals("TEAM", cleaned.getString("scoringMode"));
        assertNull(EventOptions.get(cleaned, "startsAt"));
        assertNull(EventOptions.get(cleaned, "config.signupChannelId"));
        assertEquals("123", EventOptions.get(cleaned, "config.reviewChannelId"));
        assertNull(EventOptions.get(cleaned, "config.signupClosesAt"));
        assertNull(EventOptions.get(cleaned, "config.buyIn"), "a buy-in that isn't on isn't stored");
        assertFalse(cleaned.getObject("config").hasKey("queue"));
        DataObject withBuyIn = EventOptions.normalize(DataObject.fromJson("{\"config\":{\"buyIn\":{\"enabled\":true,\"amount\":1000000,\"showPot\":true}}}"));
        assertEquals(1_000_000, ((Number) EventOptions.get(withBuyIn, "config.buyIn.amount")).intValue());
    }

    @Test
    void theAnswersBecomeAnEventInput() {
        DataObject answers = EventOptions.normalize(EventOptions.standard(template("competition"), NOW));
        EventOptions.set(answers, "scoringMode", "team");
        EventOptions.set(answers, "maxParticipants", "20");
        EventInput in = EventOptions.toInput(EventOptions.normalize(answers));
        assertEquals(ScoringMode.TEAM, in.scoringMode());
        assertEquals(20, in.maxParticipants());
        assertEquals(Instant.parse("2026-10-18T10:00:00Z"), in.startsAt());
        assertEquals(Instant.parse("2026-10-25T10:00:00Z"), in.endsAt());
        assertEquals(-1, in.config().getInt("skillId"));

        DataObject bad = DataObject.fromJson("{\"name\":\"x\",\"startsAt\":\"tomorrow\"}");
        assertEquals("The start has to be a date and time.", assertThrows(IllegalArgumentException.class, () -> EventOptions.toInput(EventOptions.normalize(bad))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> EventOptions.toInput(DataObject.fromJson("{\"scope\":\"EVERYONE\"}")));
        assertThrows(IllegalArgumentException.class, () -> EventOptions.toInput(DataObject.fromJson("{\"maxParticipants\":\"lots\"}")));
        assertEquals(0, EventOptions.toInput(DataObject.fromJson("{\"maxParticipants\":\"\"}")).maxParticipants(), "a blank limit means no limit");
    }

    @Test
    void prizesAreReadFromTheAnswersAndBadKindsAreRefused() {
        DataObject a = DataObject.fromJson("{\"prizes\":[{\"place\":1,\"kind\":\"in_game\",\"description\":\"Zaros godsword\",\"split\":\"each\"},{\"place\":2,\"placeTo\":3,\"kind\":\"CLAN_POINTS\",\"amount\":500}]}");
        var prizes = EventOptions.prizesOf(a);
        assertEquals(2, prizes.size());
        assertEquals(PrizeRules.PrizeKind.IN_GAME, prizes.get(0).kind());
        assertEquals(PrizePlan.Split.EACH, prizes.get(0).split());
        assertEquals(3, prizes.get(1).placeTo());
        assertEquals(500, prizes.get(1).amount());
        assertEquals(List.of(), EventOptions.prizesOf(DataObject.empty()));
        assertThrows(IllegalArgumentException.class, () -> EventOptions.prizesOf(DataObject.fromJson("{\"prizes\":[{\"kind\":\"gold bar\"}]}")));
    }

    // ---------- the options themselves ----------

    @Test
    void everyTemplateHasUniquePathsInStepOrderAndAFewQuickQuestions() {
        for (EventTemplate t : TEMPLATES) {
            List<Option> options = EventOptions.of(t);
            Set<String> seen = new HashSet<>();
            int last = -1;
            for (Option o : options) {
                assertTrue(seen.add(o.path()), t.key() + " lists " + o.path() + " twice");
                assertTrue(o.step().ordinal() >= last, t.key() + " is out of step order at " + o.path());
                last = o.step().ordinal();
                assertFalse(o.label().isBlank(), o.path());
                if (o.type() == EventOptions.Type.CHOICE) assertFalse(o.choices().isEmpty(), o.path() + " has choices");
                if (o.type() == EventOptions.Type.CHOICE && o.standard() != null) {
                    assertTrue(o.choices().stream().anyMatch(c -> c.value().equals(String.valueOf(o.standard()))), o.path() + ": the standard is one of the choices");
                }
            }
            long quick = options.stream().filter(Option::quick).count();
            assertTrue(quick >= 3 && quick <= 6, t.key() + " asks " + quick + " quick questions");
            assertTrue(options.stream().filter(Option::quick).anyMatch(o -> o.path().equals("name")), "the name is always quick");
        }
    }

    @Test
    void theRequiredOptionsWithoutAStandardAreQuickSoTheyAreAskedFirst() {
        for (EventTemplate t : TEMPLATES) {
            for (Option o : EventOptions.of(t)) {
                if (o.required().always() && o.standard() == null && !o.path().equals("name")) assertTrue(o.quick(), t.key() + ": " + o.path());
            }
        }
    }

    @Test
    void theSchemaHasTheStepsTheOptionsAndTheStandardAnswers() {
        DataObject schema = EventOptions.schema(template("bingo"), NOW);
        assertEquals("bingo", schema.getString("template"));
        assertEquals("Bingo", schema.getString("label"));
        assertEquals(Step.values().length, schema.getArray("steps").length());
        assertEquals("BASICS", schema.getArray("steps").getObject(0).getString("key"));
        var options = schema.getArray("options");
        DataObject name = null;
        DataObject look = null;
        DataObject amount = null;
        for (int i = 0; i < options.length(); i++) {
            DataObject o = options.getObject(i);
            if (o.getString("path").equals("name")) name = o;
            if (o.getString("path").equals("config.board.look")) look = o;
            if (o.getString("path").equals("config.buyIn.amount")) amount = o;
        }
        assertTrue(name.getObject("required").getBoolean("always"));
        assertTrue(name.getBoolean("quick"));
        assertEquals("look", look.getString("widget"));
        assertEquals(BingoLooks.DEFAULT_FOR_NEW, look.getArray("choices").getObject(0).getString("value"), "the look new events get is listed first");
        assertEquals("config.buyIn.enabled", amount.getObject("required").getObject("when").getString("path"));
        assertEquals(true, amount.getObject("required").getObject("when").getBoolean("value"));
        assertEquals("config.buyIn.enabled", amount.getObject("showWhen").getString("path"));
        assertEquals("Bingo · 18 Oct", schema.getObject("standard").getString("name"));
        assertEquals(24, schema.getInt("startsInHours"));
        assertEquals(7, schema.getInt("days"));
    }

    @Test
    void aTemplatesShapeChoicesAreTheGridAndEveryShape() {
        Option layout = EventOptions.of(template("bingo")).stream().filter(o -> o.path().equals("config.board.layout")).findFirst().orElseThrow();
        assertEquals(List.of("grid", "path", "pyramid", "convergence", "tree"), layout.choices().stream().map(EventOptions.Choice::value).toList());
        Option skill = EventOptions.of(template("competition")).stream().filter(o -> o.path().equals("config.skillId")).findFirst().orElseThrow();
        assertEquals("-1", skill.choices().get(0).value());
        assertTrue(skill.numeric());
        assertEquals(1 + com.younglings.bot.runescape.RuneScapeSkillCatalog.skillCount(), skill.choices().size());
    }

    @Test
    void conditionsReadTheAnswersTheWayTheWebsiteDoes() {
        DataObject a = DataObject.fromJson("{\"config\":{\"board\":{\"layout\":\"pyramid\"},\"buyIn\":{\"enabled\":true,\"amount\":5},\"note\":\"x\"}}");
        assertTrue(EventOptions.When.is("config.board.layout", "PYRAMID").holds(a), "case doesn't matter");
        assertFalse(EventOptions.When.is("config.board.layout", "grid").holds(a));
        assertTrue(EventOptions.When.isNot("config.board.layout", "grid").holds(a));
        assertTrue(EventOptions.When.is("config.buyIn.enabled", true).holds(a));
        assertTrue(EventOptions.When.is("config.buyIn.amount", 5).holds(a));
        assertTrue(EventOptions.When.isSet("config.note").holds(a));
        assertFalse(EventOptions.When.isSet("config.missing").holds(a));
        assertFalse(EventOptions.When.isSet("config.board.layout.deeper").holds(a));
        assertTrue(EventOptions.When.is("config.board.layout", "grid").holds(DataObject.empty()) == false);
        assertEquals("a.b.c", EventOptions.get(DataObject.fromJson("{\"a\":{\"b\":{\"c\":\"a.b.c\"}}}"), "a.b.c"));
        DataObject set = DataObject.empty();
        EventOptions.set(set, "a.b.c", 3);
        assertEquals(3, ((Number) EventOptions.get(set, "a.b.c")).intValue());
        EventOptions.set(set, "a.b.c", null);
        assertNull(EventOptions.get(set, "a.b.c"));
    }
}
