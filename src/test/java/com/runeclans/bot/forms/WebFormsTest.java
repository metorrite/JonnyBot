package com.runeclans.bot.forms;

import com.runeclans.bot.forms.WebForms.Checked;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.forms.WebForms.Kind;
import com.runeclans.bot.forms.WebForms.Question;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFormsTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static Form form(boolean onePerUser, int cooldownHours, Question... questions) {
        return new Form(1, 10, "Apply", "Apply", "", true, 5L, null, null, onePerUser, cooldownHours, List.of(questions));
    }

    private static Question q(String id, Kind kind, boolean required, String... options) {
        return new Question(id, "Question " + id, kind, required, List.of(options), kind == Kind.CHECKBOX ? 3 : 50);
    }

    @Test
    void theClanApplicationTemplateIsSwitchedOffAndHasNowhereToSendAnswersYet() {
        Form template = WebForms.template("clan-application", 10).orElseThrow();

        assertFalse(template.enabled());
        assertEquals(10, template.guildId());
        assertTrue(template.questions().stream().anyMatch(x -> x.kind() == Kind.RSN));
        assertTrue(WebForms.problemsWith(template).isEmpty(), "a switched-off template can be saved as it is");
        assertTrue(WebForms.template("nope", 10).isEmpty());
    }

    @Test
    void aFormCannotBeSwitchedOnWithoutQuestionsOrAPlaceForTheAnswers() {
        Form noQuestions = new Form(1, 10, "Apply", "", "", true, 5L, null, null, false, 0, List.of());
        Form nowhere = new Form(1, 10, "Apply", "", "", true, null, null, null, false, 0, List.of(q("a", Kind.SHORT, false)));

        assertTrue(WebForms.problemsWith(noQuestions).stream().anyMatch(p -> p.contains("at least one question")));
        assertTrue(WebForms.problemsWith(nowhere).stream().anyMatch(p -> p.contains("where answers go")));
    }

    @Test
    void aChoiceNeedsTwoChoicesAndQuestionIdsMustBeUnique() {
        Form oneChoice = form(false, 0, q("a", Kind.CHOICE, true, "Only"));
        Form repeated = form(false, 0, q("a", Kind.SHORT, false), q("a", Kind.SHORT, false));

        assertTrue(WebForms.problemsWith(oneChoice).stream().anyMatch(p -> p.contains("two choices")));
        assertTrue(WebForms.problemsWith(repeated).stream().anyMatch(p -> p.contains("repeated id")));
    }

    @Test
    void aRequiredAnswerMustBeGivenAndATooLongOneIsRefused() {
        Form f = form(false, 0, q("a", Kind.SHORT, true), q("b", Kind.SHORT, false));

        assertFalse(WebForms.check(f, Map.of("a", "  ")).ok());
        assertFalse(WebForms.check(f, Map.of("a", "ok", "b", "x".repeat(51))).ok());
        Checked fine = WebForms.check(f, Map.of("a", "  hello  "));
        assertTrue(fine.ok());
        assertEquals("hello", fine.answers().get(0).value());
        assertEquals("", fine.answers().get(1).value());
    }

    @Test
    void aChoiceMustBeOneOfTheOptionsAndACheckboxBecomesYesOrNo() {
        Form f = form(false, 0, q("c", Kind.CHOICE, true, "Yes please", "No thanks"), q("k", Kind.CHECKBOX, false));

        assertFalse(WebForms.check(f, Map.of("c", "Maybe")).ok());
        Checked fine = WebForms.check(f, Map.of("c", "No thanks", "k", "true"));
        assertTrue(fine.ok());
        assertEquals("No thanks", fine.answers().get(0).value());
        assertEquals("Yes", fine.answers().get(1).value());
        assertEquals("No", WebForms.check(f, Map.of("c", "No thanks")).answers().get(1).value());
    }

    @Test
    void aRequiredCheckboxMustBeTicked() {
        Form f = form(false, 0, q("k", Kind.CHECKBOX, true));

        assertFalse(WebForms.check(f, Map.of("k", "false")).ok());
        assertTrue(WebForms.check(f, Map.of("k", "true")).ok());
    }

    @Test
    void aRunescapeNameMustLookLikeOne() {
        Form f = form(false, 0, q("r", Kind.RSN, true));

        assertFalse(WebForms.check(f, Map.of("r", "this name is far too long")).ok());
        assertFalse(WebForms.check(f, Map.of("r", "bad<name>")).ok());
        assertEquals("Zezima 1", WebForms.check(f, Map.of("r", "  Zezima   1 ")).answers().get(0).value());
    }

    @Test
    void anAnswerForAQuestionThatIsNotOnTheFormIsIgnored() {
        Form f = form(false, 0, q("a", Kind.SHORT, false));

        Checked checked = WebForms.check(f, Map.of("a", "yes", "zzz", "sneaky"));
        assertEquals(1, checked.answers().size());
    }

    @Test
    void onePerPersonStopsASecondSubmissionAndTheCooldownWaitsItOut() {
        Form once = form(true, 0, q("a", Kind.SHORT, false));
        Form daily = form(false, 24, q("a", Kind.SHORT, false));
        Instant sixHoursAgo = NOW.minus(Duration.ofHours(6));
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));

        assertTrue(WebForms.cannotSubmit(once, null, NOW).isEmpty());
        assertTrue(WebForms.cannotSubmit(once, twoDaysAgo, NOW).isPresent(), "once is once");
        assertTrue(WebForms.cannotSubmit(daily, sixHoursAgo, NOW).isPresent());
        assertTrue(WebForms.cannotSubmit(daily, twoDaysAgo, NOW).isEmpty());
        assertTrue(WebForms.cannotSubmit(daily, sixHoursAgo, NOW).get().contains("18 hours"));
    }

    @Test
    void aFormThatIsSwitchedOffTakesNothing() {
        Form off = new Form(1, 10, "Apply", "", "", false, 5L, null, null, false, 0, List.of(q("a", Kind.SHORT, false)));

        assertTrue(WebForms.cannotSubmit(off, null, NOW).isPresent());
    }
}
