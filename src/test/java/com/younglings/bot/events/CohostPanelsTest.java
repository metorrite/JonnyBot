package com.younglings.bot.events;

import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.events.EventCohostRepository.PendingJoin;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Joining from a co-host's signup panel (T-221): someone in the host's Discord joins at once with the co-host as their origin; someone who isn't gets the
 * invite and a pending join; a pending join completes when they join the host's Discord while signups are open, and is gone either way after.
 */
class CohostPanelsTest {
    private static final long HOST = 100, ALLY = 200, EVENT = 42, USER = 7;
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private EventRepository events;
    private EventService service;
    private EventCohostRepository cohosts;
    private GuildSettingsService guildSettings;
    private CohostPanels panels;
    private Event event;

    @BeforeEach
    void setUp() {
        events = mock(EventRepository.class);
        service = mock(EventService.class);
        cohosts = mock(EventCohostRepository.class);
        guildSettings = mock(GuildSettingsService.class, Mockito.RETURNS_DEEP_STUBS);
        ClanProfileRepository profiles = mock(ClanProfileRepository.class);
        when(profiles.get(anyLong())).thenReturn(Optional.empty());
        when(guildSettings.getEffective(HOST).inviteUrl()).thenReturn("https://discord.gg/example");
        panels = new CohostPanels(events, service, cohosts, guildSettings, profiles, new EventChanges());
        event = new Event(EVENT, HOST, "bingo", "Joint Bingo", "", Status.SIGNUPS, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, NOW.plusSeconds(86_400), null, 0, "{}", 1, NOW, null, false);
        when(events.eventById(EVENT)).thenReturn(Optional.of(event));
        EventRepository.Setup open = mock(EventRepository.Setup.class);
        when(events.setup(EVENT)).thenReturn(open);
        when(service.settingsOf(event)).thenReturn(EventSettings.defaults());
    }

    @Test
    void aPendingJoinLastsUntilTheDeadlineElseTheStartElseAMonth() {
        Instant deadline = NOW.plusSeconds(3_600), start = NOW.plusSeconds(7_200);
        assertEquals(deadline, CohostPanels.pendingExpiry(deadline, start, NOW));
        assertEquals(start, CohostPanels.pendingExpiry(null, start, NOW));
        assertEquals(start, CohostPanels.pendingExpiry(NOW.minusSeconds(1), start, NOW), "a deadline already past doesn't count");
        assertEquals(NOW.plus(CohostPanels.PENDING_FALLBACK), CohostPanels.pendingExpiry(null, null, NOW));
    }

    @Test
    void someoneInTheHostsDiscordJoinsAtOnceThroughTheCoHost() {
        when(service.join(HOST, EVENT, ALLY, USER, null, false, null)).thenReturn(new EventService.Joined(false, 0));
        String answer = panels.joinNow(event, ALLY, USER);
        verify(service).join(HOST, EVENT, ALLY, USER, null, false, null);
        assertTrue(answer.startsWith("You're in **Joint Bingo**"), answer);
    }

    @Test
    void theEventsOwnRulesStillDecide() {
        when(service.join(HOST, EVENT, ALLY, USER, null, false, null)).thenThrow(new EventProblem(409, "This event isn't open to players from other servers."));
        assertEquals("This event isn't open to players from other servers.", panels.joinNow(event, ALLY, USER));
    }

    @Test
    void someoneNotInTheHostsDiscordGetsTheInviteAndAPendingJoin() {
        String answer = panels.joinLater(event, ALLY, USER, NOW);
        verify(cohosts).savePendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(86_400));
        verify(service, never()).join(anyLong(), anyLong(), anyLong(), anyLong(), Mockito.any(), Mockito.anyBoolean(), Mockito.any());
        assertTrue(answer.contains("https://discord.gg/example") && answer.contains("automatically"), answer);
    }

    @Test
    void joiningTheHostsDiscordCompletesThePendingJoinOnce() {
        when(cohosts.pendingJoins(USER, HOST, NOW)).thenReturn(List.of(new PendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(3_600))));
        when(service.join(HOST, EVENT, ALLY, USER, null, false, null)).thenReturn(new EventService.Joined(false, 0));
        assertEquals(1, panels.completePendingJoins(HOST, USER, NOW));
        verify(service).join(HOST, EVENT, ALLY, USER, null, false, null);
        verify(cohosts).deletePendingJoin(EVENT, USER);
    }

    @Test
    void aPendingJoinForAnEventThatClosedIsDroppedWithoutJoining() {
        EventRepository.Setup closed = mock(EventRepository.Setup.class);
        when(closed.signupsClosed()).thenReturn(true);
        when(events.setup(EVENT)).thenReturn(closed);
        when(cohosts.pendingJoins(USER, HOST, NOW)).thenReturn(List.of(new PendingJoin(EVENT, USER, ALLY, NOW.plusSeconds(3_600))));
        assertEquals(0, panels.completePendingJoins(HOST, USER, NOW));
        verify(service, never()).join(anyLong(), anyLong(), anyLong(), anyLong(), Mockito.any(), Mockito.anyBoolean(), Mockito.any());
        verify(cohosts).deletePendingJoin(EVENT, USER);
    }

    @Test
    void theStartAnnouncementIsOffUnlessSwitchedOn() {
        assertTrue(!CohostPanels.announceStart(event));
        Event on = new Event(EVENT, HOST, "bingo", "Joint Bingo", "", Status.RUNNING, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, null, null, 0, "{\"cohostAnnounceStart\":true}", 1, NOW, null, false);
        assertTrue(CohostPanels.announceStart(on));
        verify(cohosts, never()).savePendingJoin(eq(EVENT), anyLong(), anyLong(), Mockito.any());
    }
}
