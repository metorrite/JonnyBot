package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Turning a help ticket into an event: what the event is made of, and who may press the button. */
class TicketEventTest {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private final List<Field> fields = HelpPanels.ca(1, "CA Help").fields();

    private static List<Answer> answers(String boss, String tier, String achievement, String note) {
        return List.of(new Answer("Which boss?", boss), new Answer("Tier", tier), new Answer("Specific achievement? (Optional)", achievement),
                new Answer("Have you already made attempts yourself?", "Yes"), new Answer("Where you stand / what you need", note));
    }

    @Test
    void theEventIsABossNightForTheTicketsBossNamedAfterItAndItsTier() {
        var plan = TicketEventPlan.plan(fields, answers("Vorago", "Master", "", "Need a team for hard mode"), 42, HelpExtras.defaults(1), NOW);
        assertEquals("Vorago", plan.boss());
        assertEquals("Vorago (Master) group", plan.input().name());
        assertEquals("boss-night", plan.input().template());
        assertEquals("Vorago", plan.input().config().getString("boss"));
        assertTrue(plan.input().description().contains("ticket #0042"));
        assertTrue(plan.input().description().contains("Need a team for hard mode"));
        assertEquals(NOW.plus(Duration.ofHours(24)), plan.input().startsAt());
        assertEquals(plan.input().startsAt().plus(Duration.ofDays(1)), plan.input().endsAt());
    }

    @Test
    void theServersTimingsAreUsedAndAnAchievementIsMentioned() {
        var plan = TicketEventPlan.plan(fields, answers("Solak", "", "Perfect Solak", ""), 7, new HelpExtras(1, true, 2, 3, false, ""), NOW);
        assertEquals("Solak group", plan.input().name());
        assertEquals(NOW.plus(Duration.ofHours(2)), plan.input().startsAt());
        assertEquals(plan.input().startsAt().plus(Duration.ofDays(3)), plan.input().endsAt());
        assertTrue(plan.input().description().contains("Achievement: Perfect Solak"));
    }

    @Test
    void aTicketWithoutABossCannotBecomeAnEvent() {
        var e = assertThrows(IllegalArgumentException.class, () -> TicketEventPlan.plan(fields, answers("", "Master", "", "x"), 1, HelpExtras.defaults(1), NOW));
        assertTrue(e.getMessage().contains("boss"));
    }

    @Test
    void aLongNameAndNoteAreCutToWhatAnEventAccepts() {
        var plan = TicketEventPlan.plan(fields, answers("B".repeat(200), "Master", "", "n".repeat(3000)), 1, HelpExtras.defaults(1), NOW);
        assertTrue(plan.input().name().length() <= 80);
        assertTrue(plan.input().description().length() <= 1000);
    }

    @Test
    void theNoteIsTheMembersOwnWordsNotTheBossOrTier() {
        assertEquals("Need a team", TicketEventPlan.noteOf(fields, answers("Vorago", "Master", "", "Need a team")));
        assertEquals("", TicketEventPlan.noteOf(fields, answers("Vorago", "Master", "", "")));
        assertEquals("Vorago", TicketEventPlan.answerFor(fields, answers("Vorago", "Master", "", ""), FieldPurpose.BOSS));
    }

    @Test
    void onlyHelpersStaffAndAdminsMayPressIt() {
        PanelRoles roles = new PanelRoles(Set.of(10L), Set.of(20L), Set.of());
        assertTrue(TicketEventService.mayMake(5, Set.of(), true, roles, List.of()), "admin");
        assertTrue(TicketEventService.mayMake(5, Set.of(), false, roles, List.of(5L)), "a helper on the ticket");
        assertTrue(TicketEventService.mayMake(5, Set.of(10L), false, roles, List.of()), "the panel's helper role");
        assertTrue(TicketEventService.mayMake(5, Set.of(20L), false, roles, List.of()), "the panel's staff role");
        assertFalse(TicketEventService.mayMake(5, Set.of(99L), false, roles, List.of(6L)), "the member alone");
    }

    @Test
    void theExtrasAreOffByDefaultAndTheirNumbersAreChecked() {
        assertTrue(HelpExtras.defaults(1).problems().isEmpty());
        assertFalse(HelpExtras.defaults(1).eventButton(), "off by default");
        assertFalse(new HelpExtras(1, true, -1, 1, false, "x").problems().isEmpty());
        assertFalse(new HelpExtras(1, true, 24, 0, false, "x").problems().isEmpty());
    }
}
