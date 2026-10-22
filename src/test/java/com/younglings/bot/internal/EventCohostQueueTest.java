package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.alliance.AllianceRules;
import com.younglings.bot.events.EventCohostRepository;
import com.younglings.bot.events.EventCohostRepository.Cohost;
import com.younglings.bot.events.EventCohostRepository.Status;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The host's fill queue route on the Co-hosts card (T-220): policy, seat quotas and release time, checked before anything is saved. */
class EventCohostQueueTest {
    private static final long HOST = 100, ALLY = 200, INVITED = 300, EVENT = 42;
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private EventCohostRepository cohosts;
    private EventRepository events;
    private EventService service;
    private EventCohostApi api;
    private Member actor;

    private static Event event(int max) {
        return new Event(EVENT, HOST, "bingo", "Joint Bingo", "", com.younglings.bot.events.EventRules.Status.SIGNUPS, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, NOW, null, max,
                "{\"queue\":\"FIRST_COME\"}", 1, NOW, null, false);
    }

    @BeforeEach
    void setUp() {
        cohosts = mock(EventCohostRepository.class);
        events = mock(EventRepository.class);
        service = mock(EventService.class);
        AllianceRepository alliances = mock(AllianceRepository.class);
        when(alliances.alliancesOf(anyLong())).thenReturn(List.of(new AllianceRepository.Alliance(1, "Friends", HOST, NOW)));
        when(alliances.membersOf(1)).thenReturn(List.of(new AllianceRepository.Membership(1, HOST, AllianceRules.Status.ACTIVE, null, NOW),
                new AllianceRepository.Membership(1, ALLY, AllianceRules.Status.ACTIVE, null, NOW)));
        api = new EventCohostApi(cohosts, events, service, alliances);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(cohosts.forEvent(EVENT)).thenReturn(List.of(new Cohost(EVENT, ALLY, Status.ACCEPTED, 7L, 8L, NOW, NOW), new Cohost(EVENT, INVITED, Status.INVITED, 7L, null, NOW, null)));
        when(events.cohostQuotas(EVENT)).thenReturn(Map.of());
        EventRepository.Setup open = mock(EventRepository.Setup.class);
        when(events.setup(EVENT)).thenReturn(open);
        when(events.participants(EVENT)).thenReturn(List.of());
        when(events.setConfig(anyLong(), anyLong(), anyString())).thenReturn(true);
        use(event(10));
    }

    private void use(Event e) {
        when(service.event(HOST, EVENT)).thenReturn(e);
        when(events.eventById(EVENT)).thenReturn(Optional.of(e));
    }

    private DataObject put(String body) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(HOST);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        return api.route(guild, actor, "PUT", "events/42/cohosts/queue".split("/"), () -> DataObject.fromJson(body));
    }

    @Test
    void savesThePolicyTheHostsQuotaInTheSettingsAndEachCoHostsQuota() {
        DataObject view = put("{\"queue\":\"round_robin\",\"hostQuota\":6,\"releaseHours\":48,\"quotas\":{\"200\":4}}");

        ArgumentCaptor<String> config = ArgumentCaptor.forClass(String.class);
        verify(events).setConfig(eq(HOST), eq(EVENT), config.capture());
        DataObject saved = DataObject.fromJson(config.getValue());
        assertEquals("ROUND_ROBIN", saved.getString("queue"));
        assertEquals(6, saved.getObject("queueQuota").getInt("host"));
        assertEquals(48, saved.getObject("queueQuota").getInt("releaseHours"));
        verify(cohosts).setQuotas(EVENT, Map.of(ALLY, 4));
        verify(service).fillSeats(org.mockito.ArgumentMatchers.any());
        assertTrue(view.hasKey("queue"), "the card gets the queue back to show");
    }

    @Test
    void quotasThatAddUpToMoreThanThePlayerLimitAreRefusedAndNothingIsSaved() {
        ApiError e = assertThrows(ApiError.class, () -> put("{\"hostQuota\":6,\"quotas\":{\"200\":5}}"));
        assertTrue(e.getMessage().contains("add up to 11"), e.getMessage());
        verify(events, never()).setConfig(anyLong(), anyLong(), anyString());
        verify(cohosts, never()).setQuotas(anyLong(), anyMap());
    }

    @Test
    void onlyServersThatAcceptedGetAQuota() {
        assertThrows(ApiError.class, () -> put("{\"quotas\":{\"300\":2}}"), "invited, not accepted");
        assertThrows(ApiError.class, () -> put("{\"quotas\":{\"999\":2}}"), "not a co-host at all");
        verify(cohosts, never()).setQuotas(anyLong(), anyMap());
    }

    @Test
    void quotasNeedAPlayerLimitAndAnUnknownPolicyIsRefused() {
        use(event(0));
        assertThrows(ApiError.class, () -> put("{\"hostQuota\":2}"));
        use(event(10));
        assertThrows(ApiError.class, () -> put("{\"queue\":\"LOUDEST_FIRST\"}"));
        assertThrows(ApiError.class, () -> put("{\"hostQuota\":-1}"));
    }

    @Test
    void anotherServerCannotChangeTheQueue() {
        when(service.event(ALLY, EVENT)).thenThrow(new EventProblem(404, "That event doesn't exist in this server."));
        Guild ally = mock(Guild.class);
        when(ally.getIdLong()).thenReturn(ALLY);
        assertThrows(ApiError.class, () -> api.route(ally, actor, "PUT", "events/42/cohosts/queue".split("/"), () -> DataObject.fromJson("{\"hostQuota\":1}")));
        verify(events, never()).setConfig(anyLong(), anyLong(), anyString());
    }
}
