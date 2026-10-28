package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.HubModerationRepository;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubModerationAdminApiTest {
    private HubModerationRepository moderation;
    private EventRepository events;
    private BotOwners owners;
    private HubModerationAdminApi api;
    private Guild guild;
    private Member actor;

    private static Event event(String config) {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        return new Event(42, 9, "bingo", "Open Bingo", "", Status.SIGNUPS, Scope.PUBLIC, ScoringMode.INDIVIDUAL, now, null, 0, config, 1, now, null, false);
    }

    @BeforeEach
    void setUp() {
        moderation = mock(HubModerationRepository.class);
        events = mock(EventRepository.class);
        owners = mock(BotOwners.class);
        api = new HubModerationAdminApi(moderation, events, owners);
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(actor.getId()).thenReturn("7");
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(events.sharedBeyondServer(anyInt())).thenReturn(List.of());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void someoneWhoIsNotTheOwnerSeesNothingAndCanChangeNothing() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);
        DataObject view = api.route(guild, actor, "GET", new String[]{"hub-moderation"}, DataObject::empty);
        assertFalse(view.getBoolean("canEdit"));
        assertEquals(0, view.getArray("reports").length());
        verify(moderation, never()).openReports();
        assertThrows(ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"hub-moderation", "events", "42", "unlist"}, () -> DataObject.empty().put("reason", "spam")));
        assertThrows(ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"hub-moderation", "servers", "9", "bar"}, () -> DataObject.empty().put("reason", "spam")));
        verify(events, never()).setConfig(anyLong(), anyLong(), anyString());
        verify(moderation, never()).bar(anyLong(), anyString(), anyLong());
    }

    @Test
    void unlistingSetsTheTakedownWithItsReasonAndClosesTheReports() {
        when(events.eventById(42)).thenReturn(Optional.of(event("{\"hub\":{\"publicStandings\":true}}")));
        api.route(guild, actor, "POST", new String[]{"hub-moderation", "events", "42", "unlist"}, () -> DataObject.empty().put("reason", "Advertises gold selling"));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(events).setConfig(eq(9L), eq(42L), saved.capture());
        DataObject config = DataObject.fromJson(saved.getValue());
        assertTrue(EventListing.takenDown(config));
        assertEquals("Advertises gold selling", EventListing.takedownReason(config));
        assertTrue(config.getObject("hub").getBoolean("publicStandings"), "the host's own hub settings stay");
        verify(moderation).resolveAllFor(42, 7);
    }

    @Test
    void aReasonIsNeededAndRelistingNeedsATakedown() {
        when(events.eventById(42)).thenReturn(Optional.of(event("{}")));
        assertThrows(ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"hub-moderation", "events", "42", "unlist"}, () -> DataObject.empty().put("reason", " ")));
        assertThrows(ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"hub-moderation", "events", "42", "relist"}, DataObject::empty));
        when(events.eventById(42)).thenReturn(Optional.of(event("{\"hub\":{\"takedown\":{\"reason\":\"x\"}}}")));
        api.route(guild, actor, "POST", new String[]{"hub-moderation", "events", "42", "relist"}, DataObject::empty);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(events).setConfig(eq(9L), eq(42L), saved.capture());
        assertFalse(EventListing.takenDown(DataObject.fromJson(saved.getValue())));
    }

    @Test
    void reportsCloseOnlyAsDismissedOrActioned() {
        assertThrows(ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"hub-moderation", "reports", "3", "resolve"}, () -> DataObject.empty().put("status", "MAYBE")));
        when(moderation.resolve(3, "DISMISSED", 7)).thenReturn(true);
        api.route(guild, actor, "POST", new String[]{"hub-moderation", "reports", "3", "resolve"}, () -> DataObject.empty().put("status", "dismissed"));
        verify(moderation).resolve(3, "DISMISSED", 7);
    }
}
