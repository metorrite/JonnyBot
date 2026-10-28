package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.ticket.TicketModels.FieldPurpose;
import com.runeclans.bot.ticket.TicketModels.HelpKind;
import com.runeclans.bot.ticket.TicketModels.PanelDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The standard Informational panel: general questions, no boss or tier, none of the PvM Help rules. */
class InformationalPanelTest {
    private final PanelDefinition info = HelpPanels.info(1, HelpPanels.INFO_NAME);

    @Test
    void itIsAnInformationalPanelThatFollowsNoHelpRules() {
        assertEquals(HelpKind.INFO, info.panel().helpKind());
        assertTrue(info.panel().isInformational());
        assertFalse(info.panel().isHelpPanel(), "no helper pings, member/guest rules or escalation to Helper+");
        assertFalse(info.panel().usesHelpers(), "nobody joins as a helper");
        assertTrue(info.panel().enabled());
    }

    @Test
    void itAsksOneRequiredQuestionAndNoBossOrTier() {
        assertEquals(1, info.fields().size());
        var question = info.fields().getFirst();
        assertTrue(question.required());
        assertEquals(FieldPurpose.NONE, question.purpose());
        assertFalse(HelpTicketFlow.applies(info.panel(), info.fields()), "it uses the single form, not the boss/tier steps");
        assertTrue(HelpRules.validatePanelHelp(info.panel(), info.fields()).isEmpty());
    }

    @Test
    void theOtherPanelsAreUnchanged() {
        assertTrue(HelpPanels.pvm(1, "x").panel().isHelpPanel());
        assertTrue(HelpPanels.ca(1, "x").panel().isHelpPanel());
        assertFalse(HelpPanels.pvm(1, "x").panel().isInformational());
    }

    @Test
    void theKindSurvivesBeingSavedAsText() {
        assertEquals(HelpKind.INFO, HelpKind.valueOf(HelpKind.INFO.name()));
    }
}
