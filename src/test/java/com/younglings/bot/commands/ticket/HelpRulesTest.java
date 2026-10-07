package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.HelpKind;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpRulesTest {
    private static final HelpSettings DEFAULTS = HelpSettings.defaults(1);

    private static Panel panel(HelpKind kind) {
        return new Panel(1, 1, "CA Help", "CA Help", "", "Open", null, "ca-{number}", "", true, 1, 500L, 2, 72, 600L, null, null, "{user} Welcome", true, true, kind);
    }

    private static final Field BOSS = new Field(10, 1, 0, "Boss", FieldKind.SHORT, true, null, null, List.of());
    private static final Field TIER = new Field(11, 1, 1, "Tier", FieldKind.SELECT, true, null, null, List.of(
            new Option(110, 11, 0, "Hard", null, null), new Option(111, 11, 1, "Master", null, null), new Option(112, 11, 2, "Grandmaster", null, null)),
            FieldPurpose.TIER);
    private static final Field ATTEMPTS = new Field(12, 1, 2, "Earlier attempts", FieldKind.PARAGRAPH, false, null, null, List.of(), FieldPurpose.ATTEMPTS);
    private static final List<Field> FIELDS = List.of(BOSS, TIER, ATTEMPTS);

    // ---------- pings ----------

    @Test
    void theDefaultsPingMembersAndWaitSeventyTwoHoursAndPingGuestsNever() {
        assertEquals(new HelpRules.Pings(true, 72), HelpRules.pingsFor(DEFAULTS, true));
        assertEquals(new HelpRules.Pings(false, null), HelpRules.pingsFor(DEFAULTS, false));
        assertFalse(DEFAULTS.guestPingsEnabled());
    }

    @Test
    void guestPingsFollowTheirOwnSettingsOnceSwitchedOn() {
        HelpSettings on = new HelpSettings(1, null, null, null, true, 72, true, true, 24, true, "Master, Grandmaster", null, null);
        assertEquals(new HelpRules.Pings(true, 24), HelpRules.pingsFor(on, false));
        assertEquals(new HelpRules.Pings(true, 72), HelpRules.pingsFor(on, true), "members are unaffected");

        HelpSettings onlyLater = new HelpSettings(1, null, null, null, true, 72, true, false, null, true, "Master", null, null);
        assertEquals(new HelpRules.Pings(false, null), HelpRules.pingsFor(onlyLater, false));
    }

    @Test
    void memberTimersCanBeChanged() {
        HelpSettings quiet = new HelpSettings(1, null, null, null, false, null, false, true, null, true, "Master", null, null);
        assertEquals(new HelpRules.Pings(false, null), HelpRules.pingsFor(quiet, true));
    }

    // ---------- earlier attempts ----------

    @Test
    void aGuestAskingForMasterOrGrandmasterOnACaPanelMustDescribeEarlierAttempts() {
        Map<Long, String> noAttempts = Map.of(10L, "Vorago", 11L, "111");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), FIELDS, noAttempts, false).isPresent());
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), FIELDS, Map.of(10L, "x", 11L, "112", 12L, "   "), false).isPresent(), "blank doesn't count");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), FIELDS, noAttempts, false).get().contains("Master"));

        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), FIELDS, Map.of(10L, "x", 11L, "111", 12L, "3 attempts, died to phase 4"), false).isEmpty());
    }

    @Test
    void membersHardAndBelowOtherPanelsAndSwitchedOffRulesAreNeverHeldUp() {
        Map<Long, String> master = Map.of(10L, "x", 11L, "111");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), FIELDS, master, true).isEmpty(), "members are exempt");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), FIELDS, Map.of(10L, "x", 11L, "110"), false).isEmpty(), "Hard and below need nothing");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.PVM), FIELDS, master, false).isEmpty(), "general PvM help never asks");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.NONE), FIELDS, master, false).isEmpty());

        HelpSettings off = new HelpSettings(1, null, null, null, true, 72, false, true, null, false, "Master, Grandmaster", null, null);
        assertTrue(HelpRules.attemptsProblem(off, panel(HelpKind.CA), FIELDS, master, false).isEmpty());
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), List.of(BOSS, TIER), master, false).isEmpty(), "no attempts question, nothing to require");
    }

    @Test
    void theTierNamesAreMatchedIgnoringCaseAndSpaces() {
        HelpSettings custom = new HelpSettings(1, null, null, null, true, 72, false, true, null, true, " elite ,, MASTER ", null, null);
        assertEquals(List.of("elite", "MASTER"), HelpRules.highTierLabels(custom));
        assertTrue(HelpRules.attemptsProblem(custom, panel(HelpKind.CA), FIELDS, Map.of(10L, "x", 11L, "111"), false).isPresent());
    }

    // ---------- settings ----------

    @Test
    void sensibleSettingsAreValidAndEachMistakeIsReported() {
        assertTrue(HelpRules.validate(DEFAULTS).isEmpty());

        HelpSettings bad = new HelpSettings(1, null, null, "x".repeat(HelpSettings.MAX_GUIDELINES + 1), true, 0, true, true, 9999, true, " , ", null, null);
        List<String> problems = HelpRules.validate(bad);
        assertEquals(4, problems.size(), problems.toString());
    }

    @Test
    void guidelinesFallBackToTheDraftAndTheDraftFitsAMessage() {
        assertEquals(HelpSettings.DEFAULT_GUIDELINES, DEFAULTS.guidelinesOrDefault());
        assertEquals("custom", new HelpSettings(1, null, null, "custom", true, 72, false, true, null, true, "Master", null, null).guidelinesOrDefault());
        assertTrue(HelpSettings.DEFAULT_GUIDELINES.length() < HelpSettings.MAX_GUIDELINES);
        assertTrue(HelpSettings.DEFAULT_GUIDELINES.contains("not going in game"), "it says help is advice, not in-game");
    }

    @Test
    void theGuidelinesMessageFitsDiscordsTextLimit() {
        var message = HelpOnboarding.message(DEFAULTS);
        int total = net.dv8tion.jda.api.components.tree.ComponentTree.of(List.of(message)).findAll(net.dv8tion.jda.api.components.textdisplay.TextDisplay.class)
                .stream().mapToInt(t -> t.getContent().length()).sum();
        assertTrue(total <= 4000, "Components V2 messages hold at most 4000 characters of text, this one has " + total);
        new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder().useComponentsV2(true).setComponents(message).build();
    }

    // ---------- panels ----------

    @Test
    void aCaPanelNeedsARequiredTierDropdownAndMarksAreOnlyForHelpPanels() {
        assertTrue(HelpRules.validatePanelHelp(panel(HelpKind.CA), FIELDS).isEmpty());
        assertEquals(1, HelpRules.validatePanelHelp(panel(HelpKind.CA), List.of(BOSS)).size(), "no tier question on a CA panel");
        assertTrue(HelpRules.validatePanelHelp(panel(HelpKind.PVM), List.of(BOSS)).isEmpty(), "general help needs nothing");
        assertEquals(1, HelpRules.validatePanelHelp(panel(HelpKind.NONE), FIELDS).size(), "an ordinary panel can't mark questions");
        assertTrue(HelpRules.validatePanelHelp(panel(HelpKind.NONE), List.of(BOSS)).isEmpty());
    }

    @Test
    void marksMustFitTheirQuestionsAndAppearOnce() {
        Field textTier = new Field(13, 1, 3, "Tier typed", FieldKind.SHORT, true, null, null, List.of(), FieldPurpose.TIER);
        Field checkboxAttempts = new Field(14, 1, 4, "Tried?", FieldKind.CHECKBOX, false, null, null, List.of(), FieldPurpose.ATTEMPTS);
        List<String> problems = HelpRules.validatePanelHelp(panel(HelpKind.PVM), List.of(TIER, textTier, ATTEMPTS, checkboxAttempts));
        assertEquals(4, problems.size(), problems.toString());
    }
}
