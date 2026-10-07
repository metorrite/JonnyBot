package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketTextTest {
    private static final Panel PANEL = new Panel(4, 1, "CA Help", "Combat Achievement Help", "", "Open", null, "ca-{number}", "", true, 1, null, 2, 24, null, null, null);

    private static Ticket ticket(Status status, String routing) {
        return new Ticket(9, 1, 4L, 12, 55L, 100L, status, null, routing, List.of(), 66L, OffsetDateTime.now(), null, null, null, null, false);
    }

    @Test
    void everyPlaceholderIsFilledIn() {
        String out = TicketText.fill("{user}|{number}|{panel}|{type}|{rsn}|{ping}|{helpers}", PANEL, ticket(Status.OPEN, "Elite"), List.of("Metorrite", "Alt One"), List.of(201L, 202L), 77L);
        assertEquals("<@100>|0012|Combat Achievement Help|Elite|Metorrite, Alt One|<@&77>|<@201>, <@202>", out);
    }

    @Test
    void missingThingsFillInAsNothingAndNobodyYet() {
        String out = TicketText.fill("[{type}][{rsn}][{ping}][{helpers}]", PANEL, ticket(Status.OPEN, null), List.of(), List.of(), null);
        assertEquals("[][][][nobody yet]", out);
    }

    @Test
    void aClosedTicketNoLongerPings() {
        assertFalse(TicketText.fill("hello {ping}", PANEL, ticket(Status.CLOSED, null), List.of(), List.of(), 77L).contains("<@&77>"));
    }

    @Test
    void theWordingIsTrimmedAndUnknownTextIsLeftAlone() {
        assertEquals("Hi {nope}", TicketText.fill("  Hi {nope} {ping}  ", PANEL, ticket(Status.OPEN, null), List.of(), List.of(), null));
    }

    @Test
    void itKnowsWhetherTheWordingPlacesThePing() {
        assertTrue(TicketText.usesPing("{user} {ping}"));
        assertFalse(TicketText.usesPing("{user} Welcome"));
        assertFalse(TicketText.usesPing(null));
    }
}
