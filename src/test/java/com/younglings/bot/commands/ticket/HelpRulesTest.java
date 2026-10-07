package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.HelpKind;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---------- tiers and ping settings ----------

    @Test
    void aHelperCanBePingedBelowMasterAndAHelperPlusForEveryTier() {
        assertEquals(List.of("Easy", "Medium", "Hard", "Elite", "General"), HelpRules.allowedTiers(DEFAULTS, false));
        assertEquals(List.of("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster", "General"), HelpRules.allowedTiers(DEFAULTS, true));
    }

    @Test
    void whichTiersAreHighFollowsTheSettingsNotAFixedList() {
        HelpSettings onlyGrandmaster = new HelpSettings(1, null, null, null, true, 72, false, true, null, true, "grandmaster", null, null);
        assertEquals(List.of("Easy", "Medium", "Hard", "Elite", "Master", "General"), HelpRules.allowedTiers(onlyGrandmaster, false));
    }

    @Test
    void aTicketsPingGroupIsItsTierOrGeneral() {
        assertEquals("Master", HelpRules.pingGroup(" master "));
        assertEquals("Elite", HelpRules.pingGroup("Elite"));
        assertEquals(HelpRules.GENERAL, HelpRules.pingGroup(null));
        assertEquals(HelpRules.GENERAL, HelpRules.pingGroup(""));
        assertEquals(HelpRules.GENERAL, HelpRules.pingGroup("Legendary"));
    }

    @Test
    void theTierIsReadFromTheQuestionMarkedAsTheTier() {
        assertEquals("Master", HelpRules.tierOf(FIELDS, Map.of(10L, "x", 11L, "111")));
        assertNull(HelpRules.tierOf(FIELDS, Map.of(10L, "x")), "left blank");
        assertNull(HelpRules.tierOf(List.of(BOSS), Map.of(10L, "x")), "no tier question");
    }

    @Test
    void thePingSettingsShowOnlyWhatTheRoleAllowsAndTickWhatWasChosen() {
        var helper = HelpOnboarding.pingSettings(DEFAULTS, HelpPingService.HelperLevel.HELPER, java.util.Set.of("Easy", "Grandmaster"));
        var menu = ComponentTree.of(List.of(helper)).findAll(net.dv8tion.jda.api.components.selections.StringSelectMenu.class).getFirst();
        assertEquals(List.of("Easy", "Medium", "Hard", "Elite", "General"), menu.getOptions().stream().map(o -> o.getValue()).toList());
        assertEquals(List.of("Easy"), menu.getOptions().stream().filter(o -> o.isDefault()).map(o -> o.getValue()).toList(), "a tier the role can't have is never shown as ticked");
        assertEquals(0, menu.getMinValues(), "clearing everything turns pings off");
        new MessageCreateBuilder().useComponentsV2(true).setComponents(helper).build();

        var plus = HelpOnboarding.pingSettings(DEFAULTS, HelpPingService.HelperLevel.HELPER_PLUS, java.util.Set.of("Grandmaster"));
        var plusMenu = ComponentTree.of(List.of(plus)).findAll(net.dv8tion.jda.api.components.selections.StringSelectMenu.class).getFirst();
        assertEquals(7, plusMenu.getOptions().size());
        assertEquals(List.of("Grandmaster"), plusMenu.getOptions().stream().filter(o -> o.isDefault()).map(o -> o.getValue()).toList());
    }

    @Test
    void theSignupMessageHasTheAgreePingSettingsAndStepDownButtons() {
        var buttons = ComponentTree.of(List.of(HelpOnboarding.message(DEFAULTS))).findAll(net.dv8tion.jda.api.components.buttons.Button.class);
        assertEquals(List.of("pvmhelp_accept", "pvmhelp_pings", "pvmhelp_leave"), buttons.stream().map(b -> b.getCustomId()).toList());
    }

    @Test
    void theTwoTestPanelsAreValidHelpPanels() {
        for (var definition : List.of(DevHelpSetup.pvmPanel(1), DevHelpSetup.caPanel(1))) {
            assertTrue(TicketRules.validatePanel(definition.panel(), definition.fields()).isEmpty(), TicketRules.validatePanel(definition.panel(), definition.fields()).toString());
            assertTrue(HelpRules.validatePanelHelp(definition.panel(), definition.fields()).isEmpty(), HelpRules.validatePanelHelp(definition.panel(), definition.fields()).toString());
        }
        assertEquals(HelpKind.PVM, DevHelpSetup.pvmPanel(1).panel().helpKind());
        assertEquals(HelpKind.CA, DevHelpSetup.caPanel(1).panel().helpKind());

        // PvM help: only the boss is required. CA help: the boss and the tier are required, and the tier is the marked dropdown.
        var pvm = DevHelpSetup.pvmPanel(1).fields();
        assertEquals(List.of(true, false, false, false, false), pvm.stream().map(f -> f.required()).toList());
        var ca = DevHelpSetup.caPanel(1).fields();
        assertTrue(ca.get(0).required() && ca.get(1).required());
        assertEquals(com.younglings.bot.ticket.TicketModels.FieldPurpose.TIER, ca.get(1).purpose());
        assertEquals(com.younglings.bot.ticket.TicketModels.FieldPurpose.ATTEMPTS, ca.get(3).purpose());
        assertEquals(FieldKind.CHECKBOX, ca.get(3).kind(), "earlier attempts is a yes/no checkbox");
        assertEquals(com.younglings.bot.ticket.TicketModels.FieldPurpose.BOSS, ca.get(0).purpose());
        assertEquals(com.younglings.bot.ticket.TicketModels.FieldPurpose.ACHIEVEMENT, ca.get(2).purpose());
    }

    @Test
    void withACheckboxAGuestAskingForMasterMustAnswerYes() {
        Field checkbox = new Field(12, 1, 2, "Have you already made attempts yourself?", FieldKind.CHECKBOX, false, null, null, List.of(), FieldPurpose.ATTEMPTS);
        List<Field> fields = List.of(BOSS, TIER, checkbox);

        var no = HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), fields, Map.of(11L, "111", 12L, "false"), false);
        assertTrue(no.isPresent());
        assertTrue(no.get().contains("Master") && no.get().contains("Yes"), no.get());
        assertFalse(no.get().toLowerCase().contains("clan"), "it never talks about the clan");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), fields, Map.of(11L, "111"), false).isPresent(), "unanswered is no");

        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), fields, Map.of(11L, "111", 12L, "true"), false).isEmpty());
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.CA), fields, Map.of(11L, "110", 12L, "false"), false).isEmpty(), "Hard needs nothing");
        assertTrue(HelpRules.attemptsProblem(DEFAULTS, panel(HelpKind.PVM), fields, Map.of(11L, "111", 12L, "false"), false).isEmpty(), "PvM help never blocks");
    }

    @Test
    void bossAndAchievementMustBeWrittenQuestionsAndABossNeedsATier() {
        Field boss = new Field(20, 1, 0, "Which boss?", FieldKind.SHORT, true, null, null, List.of(), FieldPurpose.BOSS);
        Field achievement = new Field(21, 1, 2, "Specific achievement?", FieldKind.SHORT, false, null, null, List.of(), FieldPurpose.ACHIEVEMENT);
        Field checkbox = new Field(22, 1, 3, "Tried?", FieldKind.CHECKBOX, false, null, null, List.of(), FieldPurpose.ATTEMPTS);
        assertTrue(HelpRules.validatePanelHelp(panel(HelpKind.CA), List.of(boss, TIER, achievement, checkbox)).isEmpty());

        assertEquals(1, HelpRules.validatePanelHelp(panel(HelpKind.PVM), List.of(boss)).size(), "a boss question needs a tier question too");
        Field dropdownBoss = new Field(20, 1, 0, "Which boss?", FieldKind.SELECT, true, null, null, List.of(new Option(1, 20, 0, "x", null, null)), FieldPurpose.BOSS);
        assertEquals(1, HelpRules.validatePanelHelp(panel(HelpKind.PVM), List.of(dropdownBoss, TIER)).size());
        assertEquals(1, HelpRules.validatePanelHelp(panel(HelpKind.PVM), List.of(boss, boss, TIER)).size(), "only one boss question");
        assertEquals(1, HelpRules.validatePanelHelp(panel(HelpKind.NONE), List.of(boss)).size(), "an ordinary panel can't mark questions");
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
        // two tier questions, two attempts questions, and the typed tier isn't a dropdown; a checkbox is a fine way to ask about attempts
        assertEquals(3, problems.size(), problems.toString());
    }
}
