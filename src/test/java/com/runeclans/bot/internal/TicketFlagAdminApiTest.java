package com.runeclans.bot.internal;

import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.ticket.TicketModels.Status;
import com.runeclans.bot.ticket.TicketModels.Ticket;
import com.runeclans.bot.ticket.TicketRepository;
import com.runeclans.bot.ticketflag.TicketFlagRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The ticket flags: off by default, staff-only, scoped to the server, with a bounded note. */
class TicketFlagAdminApiTest {
    private static final long GUILD = 1L;

    private TicketFlagRepository flags;
    private TicketRepository tickets;
    private TicketFlagAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        flags = mock(TicketFlagRepository.class);
        tickets = mock(TicketRepository.class);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        User user = mock(User.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(actor.getUser()).thenReturn(user);
        when(user.getIdLong()).thenReturn(9L);
        api = new TicketFlagAdminApi(flags, tickets);
    }

    private Ticket ticket(long id, long guildId) {
        return new Ticket(id, guildId, 3L, 12, 100L, 55L, Status.CLOSED, null, null, List.of(), null, null, null, null, null, null, false);
    }

    private DataObject call(String method, String body, String... parts) {
        return api.route(guild, actor, method, parts, () -> DataObject.fromJson(body));
    }

    @Test
    void otherPathsAreNotThisFeatures() {
        assertNull(api.route(guild, actor, "GET", new String[]{"tickets"}, () -> null));
    }

    @Test
    void theFeatureIsOffByDefault() {
        when(flags.list(GUILD, null, 200)).thenReturn(List.of());

        assertFalse(call("GET", "{}", "ticket-flags").getBoolean("enabled"));
    }

    @Test
    void aFlagCannotBeAddedWhileTheFeatureIsOff() {
        when(tickets.getTicket(7L)).thenReturn(ticket(7L, GUILD));

        assertThrows(ApiError.class, () -> call("POST", "{\"note\":\"rude\"}", "ticket-flags", "ticket", "7"));
        verify(flags, never()).add(anyLong(), anyLong(), anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    void aFlagIsRecordedOnTheTicketsPlayer() {
        when(flags.enabled(GUILD)).thenReturn(true);
        when(tickets.getTicket(7L)).thenReturn(ticket(7L, GUILD));

        call("POST", "{\"note\":\"  Refused to listen  \"}", "ticket-flags", "ticket", "7");

        verify(flags).add(GUILD, 7L, 12, 55L, 9L, "Refused to listen");
    }

    @Test
    void aTicketFromAnotherServerIsNotFound() {
        when(flags.enabled(GUILD)).thenReturn(true);
        when(tickets.getTicket(7L)).thenReturn(ticket(7L, 2L));

        assertEquals(404, assertThrows(ApiError.class, () -> call("POST", "{\"note\":\"x\"}", "ticket-flags", "ticket", "7")).status);
    }

    @Test
    void theNoteMustBeThereAndShort() {
        when(flags.enabled(GUILD)).thenReturn(true);
        when(tickets.getTicket(7L)).thenReturn(ticket(7L, GUILD));

        assertThrows(ApiError.class, () -> call("POST", "{\"note\":\"   \"}", "ticket-flags", "ticket", "7"));
        assertThrows(ApiError.class, () -> call("POST", "{\"note\":\"" + "a".repeat(TicketFlagAdminApi.MAX_NOTE + 1) + "\"}", "ticket-flags", "ticket", "7"));
        verify(flags, never()).add(anyLong(), anyLong(), anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    void switchingItOnIsSaved() {
        call("PUT", "{\"enabled\":true}", "ticket-flags", "settings");

        verify(flags).setEnabled(GUILD, true);
    }

    @Test
    void aFlagCanBeRemovedAndAMissingOneIs404() {
        when(flags.delete(GUILD, 4L)).thenReturn(true);
        when(flags.delete(GUILD, 5L)).thenReturn(false);

        assertTrue(call("POST", "{}", "ticket-flags", "4", "delete").getBoolean("deleted"));
        assertEquals(404, assertThrows(ApiError.class, () -> call("POST", "{}", "ticket-flags", "5", "delete")).status);
    }

    @Test
    void theTicketViewListsEveryFlagOnThatPlayer() {
        when(tickets.getTicket(7L)).thenReturn(ticket(7L, GUILD));
        when(flags.list(eq(GUILD), eq(55L), anyInt())).thenReturn(List.of());

        call("GET", "{}", "ticket-flags", "ticket", "7");

        verify(flags).list(GUILD, 55L, 200);
    }
}
