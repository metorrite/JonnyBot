package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketRulesTest {
    private static final long HELPER_ROLE = 10, STAFF_ROLE = 11, OTHER_ROLE = 12, REQUESTER = 100, HELPER_USER = 101;

    private static Panel panel(Integer helperCap, Integer escalationHours) {
        return new Panel(1, 1, "CA Help", "CA Help", "", "Open", null, "ca-{number}", "", true, 1, 500L, helperCap, escalationHours, 600L, null, null);
    }

    private static Ticket ticket(Status status) {
        return new Ticket(7, 1, 1L, 3, 55L, REQUESTER, status, null, null, List.of(), 66L, OffsetDateTime.now(), null, null, null, null, false);
    }

    private static final PanelRoles ROLES = new PanelRoles(Set.of(HELPER_ROLE), Set.of(STAFF_ROLE));

    // ---------- channel names ----------

    @Test
    void theTemplateIsFilledInAndCleanedUp() {
        assertEquals("ca-0003", TicketRules.channelName("ca-{number}", 3, "Metorrite", null));
        assertEquals("ticket-0003-some-user", TicketRules.channelName("Ticket {number} {user}", 3, "Some User!", null));
        assertEquals("ca-0012-grandmaster", TicketRules.channelName("ca-{number}-{type}", 12, "x", "Grandmaster"));
    }

    @Test
    void aNameIsNeverEmptyOrTooLong() {
        assertEquals("ticket-0005", TicketRules.channelName("{user}", 5, "!!!", null));
        assertEquals("ticket-0005", TicketRules.channelName("", 5, "a", null));
        assertTrue(TicketRules.channelName("x".repeat(300) + "{number}", 1, "a", null).length() <= 100);
    }

    @Test
    void symbolsAndRepeatedHyphensCollapseAndTheNameNeverStartsWithAHyphen() {
        assertEquals("a-b-c", TicketRules.channelName("a !! b -- c", 1, "", null));
        assertFalse(TicketRules.channelName("--hi--", 1, "", null).startsWith("-"));
        assertEquals("hi", TicketRules.channelName("--hi--", 1, "", null));
    }

    // ---------- permissions ----------

    @Test
    void theChannelIsHiddenFromEveryoneAndOpenToTheRequesterRolesAndTheBot() {
        List<TicketRules.Overwrite> overwrites = TicketRules.overwrites(1L, REQUESTER, 999L, Set.of(HELPER_ROLE, STAFF_ROLE));

        var everyone = overwrites.stream().filter(o -> o.id() == 1L).findFirst().orElseThrow();
        assertTrue(everyone.isRole());
        assertEquals(Permission.getRaw(Permission.VIEW_CHANNEL), everyone.deny());
        assertEquals(0, everyone.allow());

        var requester = overwrites.stream().filter(o -> o.id() == REQUESTER).findFirst().orElseThrow();
        assertFalse(requester.isRole());
        assertTrue((requester.allow() & Permission.getRaw(Permission.VIEW_CHANNEL)) != 0);
        assertTrue((requester.allow() & Permission.getRaw(Permission.MESSAGE_SEND)) != 0);

        assertEquals(2, overwrites.stream().filter(o -> o.isRole() && o.id() != 1L).count());
        var bot = overwrites.stream().filter(o -> o.id() == 999L).findFirst().orElseThrow();
        assertTrue((bot.allow() & Permission.getRaw(Permission.MANAGE_CHANNEL)) != 0, "the bot must be able to delete the channel later");
    }

    @Test
    void helperAndStaffRolesAreBothParticipants() {
        assertEquals(Set.of(HELPER_ROLE, STAFF_ROLE), TicketRules.participantRoles(ROLES));
    }

    // ---------- joining ----------

    @Test
    void aHelperJoinsWhileThereIsRoom() {
        assertEquals(TicketRules.JoinResult.OK, TicketRules.canJoin(panel(2, null), ROLES, Set.of(HELPER_ROLE), false, false, false, 1, true));
    }

    @Test
    void theCapStopsHelpersButNotStaffOrAdmins() {
        assertEquals(TicketRules.JoinResult.FULL, TicketRules.canJoin(panel(2, null), ROLES, Set.of(HELPER_ROLE), false, false, false, 2, true));
        assertEquals(TicketRules.JoinResult.OK, TicketRules.canJoin(panel(2, null), ROLES, Set.of(STAFF_ROLE), false, false, false, 2, true));
        assertEquals(TicketRules.JoinResult.OK, TicketRules.canJoin(panel(2, null), ROLES, Set.of(OTHER_ROLE), true, false, false, 5, true));
    }

    @Test
    void joiningNeedsARoleAndRefusesTheRequesterRepeatsAndClosedTickets() {
        assertEquals(TicketRules.JoinResult.NOT_ELIGIBLE, TicketRules.canJoin(panel(2, null), ROLES, Set.of(OTHER_ROLE), false, false, false, 0, true));
        assertEquals(TicketRules.JoinResult.IS_REQUESTER, TicketRules.canJoin(panel(2, null), ROLES, Set.of(HELPER_ROLE), false, true, false, 0, true));
        assertEquals(TicketRules.JoinResult.ALREADY_HELPER, TicketRules.canJoin(panel(2, null), ROLES, Set.of(HELPER_ROLE), false, false, true, 1, true));
        assertEquals(TicketRules.JoinResult.TICKET_CLOSED, TicketRules.canJoin(panel(2, null), ROLES, Set.of(HELPER_ROLE), false, false, false, 0, false));
    }

    @Test
    void aPanelWithoutAHelperLimitHasNoHelperSystem() {
        assertEquals(TicketRules.JoinResult.NO_HELPER_SYSTEM, TicketRules.canJoin(panel(null, null), ROLES, Set.of(HELPER_ROLE), false, false, false, 0, true));
    }

    // ---------- closing ----------

    @Test
    void theRequesterHelpersStaffAndAdminsCanCloseAndNobodyElse() {
        Ticket open = ticket(Status.OPEN);
        assertTrue(TicketRules.canClose(open, REQUESTER, Set.of(), ROLES, false, List.of()));
        assertTrue(TicketRules.canClose(open, HELPER_USER, Set.of(), ROLES, false, List.of(HELPER_USER)));
        assertTrue(TicketRules.canClose(open, 555, Set.of(STAFF_ROLE), ROLES, false, List.of()));
        assertTrue(TicketRules.canClose(open, 555, Set.of(), ROLES, true, List.of()));
        assertFalse(TicketRules.canClose(open, 555, Set.of(HELPER_ROLE), ROLES, false, List.of()), "a helper role alone isn't enough — they must have joined");
        assertFalse(TicketRules.canClose(ticket(Status.CLOSED), REQUESTER, Set.of(), ROLES, true, List.of()), "a closed ticket can't be closed again");
    }

    // ---------- routing ----------

    private static final List<Field> FIELDS = List.of(
            new Field(1, 1, 0, "Achievement", FieldKind.SHORT, true, null, null, List.of()),
            new Field(2, 1, 1, "Tier", FieldKind.SELECT, true, null, null, List.of(
                    new Option(20, 2, 0, "Easy", 700L, 710L), new Option(21, 2, 1, "Elite", 701L, null), new Option(22, 2, 2, "Plain", null, null))));

    @Test
    void theChosenTierPicksTheRoleToPingAndEscalateTo() {
        Option elite = TicketRules.routingOption(FIELDS, f -> f.id() == 2 ? "21" : null).orElseThrow();
        assertEquals("Elite", elite.label());
        assertEquals(701L, TicketRules.pingRole(panel(2, 24), elite));
        assertEquals(600L, TicketRules.escalateRole(panel(2, 24), elite), "no escalation role on that choice, so the panel's default");
    }

    @Test
    void aChoiceWithNoRolesFallsBackToThePanelDefaults() {
        Option plain = TicketRules.routingOption(FIELDS, f -> "22").orElseThrow();
        assertEquals(500L, TicketRules.pingRole(panel(2, 24), plain));
        assertEquals(500L, TicketRules.pingRole(panel(2, 24), null));
    }

    @Test
    void withNoRoutingQuestionOrNoAnswerThereIsNoRoutingOption() {
        assertEquals(Optional.empty(), TicketRules.routingOption(List.of(FIELDS.getFirst()), f -> "x"));
        assertEquals(Optional.empty(), TicketRules.routingOption(FIELDS, f -> null));
    }

    // ---------- panel validation ----------

    @Test
    void aSensiblePanelHasNoProblems() {
        assertTrue(TicketRules.validatePanel(panel(2, 24), FIELDS).isEmpty());
    }

    @Test
    void eachMistakeIsReported() {
        Panel bad = new Panel(1, 1, " ", "", "", "", null, "t", "", true, 0, null, 99, 0, null, null, null);
        List<String> problems = TicketRules.validatePanel(bad, List.of());
        assertEquals(6, problems.size(), problems.toString());

        List<Field> tooMany = java.util.stream.IntStream.range(0, 6).mapToObj(i -> new Field(i, 1, i, "Q" + i, FieldKind.SHORT, true, null, null, List.of())).toList();
        assertTrue(TicketRules.validatePanel(panel(null, null), tooMany).stream().anyMatch(p -> p.contains("at most 5")));

        List<Field> emptyDropdown = List.of(new Field(1, 1, 0, "Pick", FieldKind.SELECT, true, null, null, List.of()));
        assertTrue(TicketRules.validatePanel(panel(null, null), emptyDropdown).stream().anyMatch(p -> p.contains("at least one choice")));
    }
}
