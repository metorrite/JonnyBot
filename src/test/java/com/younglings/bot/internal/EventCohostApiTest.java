package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.alliance.AllianceRules;
import com.younglings.bot.events.EventCohostRepository;
import com.younglings.bot.events.EventCohostRepository.Cohost;
import com.younglings.bot.events.EventCohostRepository.Status;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRoster;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.PrizeRules.BuyInStatus;
import com.younglings.bot.events.TeamRules.Kind;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may do what with a co-hosted event (T-219): the host, an accepted co-host, an invited one, a stranger and a server that left the alliance. Every
 * route checks the calling server's place in the event, so these are the ones that matter.
 */
class EventCohostApiTest {
    private static final long HOST = 100, ALLY = 200, STRANGER = 300, EVENT = 42;
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private EventCohostRepository cohosts;
    private EventRepository events;
    private EventService service;
    private AllianceRepository alliances;
    private EventCohostApi api;
    private Member actor;
    private JDA jda;

    private static Event event(com.younglings.bot.events.EventRules.Status status) {
        return new Event(EVENT, HOST, "bingo", "Joint Bingo", "", status, Scope.ALLIANCE, ScoringMode.INDIVIDUAL, NOW, null, 0, "{}", 1, NOW, null, false);
    }

    private static Participant player(long userId, long guildId) {
        return new Participant(EVENT, userId, guildId, null, guildId == HOST ? Kind.HOME_OTHER : Kind.PARTNER, false, BuyInStatus.NONE, NOW,
                EventRoster.Verification.PENDING, null, null, null, null, EventRepository.RosterSource.SELF);
    }

    private Guild guild(long id) {
        Guild g = mock(Guild.class);
        when(g.getIdLong()).thenReturn(id);
        when(g.getJDA()).thenReturn(jda);
        return g;
    }

    private EventRepository.Setup setup(boolean signupsClosed, boolean rosterConfirmed) {
        EventRepository.Setup s = mock(EventRepository.Setup.class);
        when(s.signupsClosed()).thenReturn(signupsClosed);
        when(s.rosterConfirmed()).thenReturn(rosterConfirmed);
        return s;
    }

    @BeforeEach
    void setUp() {
        cohosts = mock(EventCohostRepository.class);
        events = mock(EventRepository.class);
        service = mock(EventService.class);
        alliances = mock(AllianceRepository.class);
        api = new EventCohostApi(cohosts, events, service, alliances, mock(com.younglings.bot.events.CohostPanels.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(actor.getId()).thenReturn("7");
        jda = mock(JDA.class);
        // HOST and ALLY are both accepted members of alliance 1; STRANGER only invited
        when(alliances.alliancesOf(HOST)).thenReturn(List.of(new AllianceRepository.Alliance(1, "Friends", HOST, NOW)));
        when(alliances.membersOf(1)).thenReturn(List.of(
                new AllianceRepository.Membership(1, HOST, AllianceRules.Status.ACTIVE, null, NOW),
                new AllianceRepository.Membership(1, ALLY, AllianceRules.Status.ACTIVE, null, NOW),
                new AllianceRepository.Membership(1, STRANGER, AllianceRules.Status.INVITED, null, NOW)));
        Event e = event(com.younglings.bot.events.EventRules.Status.SIGNUPS);
        when(service.event(HOST, EVENT)).thenReturn(e);
        when(service.event(anyLongNot(HOST), org.mockito.ArgumentMatchers.eq(EVENT))).thenThrow(new EventProblem(404, "That event doesn't exist in this server."));
        when(events.eventById(EVENT)).thenReturn(Optional.of(e));
        EventRepository.Setup open = setup(false, false);
        when(events.setup(EVENT)).thenReturn(open);
        when(events.participants(EVENT)).thenReturn(List.of(player(1, HOST), player(2, ALLY), player(3, STRANGER)));
        when(cohosts.forEvent(EVENT)).thenReturn(List.of());
        when(cohosts.get(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    private static long anyLongNot(long value) {
        return org.mockito.ArgumentMatchers.longThat(v -> v != value);
    }

    private DataObject call(long guildId, String method, String path, DataObject body) {
        return api.route(guild(guildId), actor, method, path.split("/"), () -> body);
    }

    @Test
    void theHostInvitesOnlyAcceptedAllianceMembers() {
        call(HOST, "POST", "events/42/cohosts", DataObject.empty().put("guildId", Long.toString(ALLY)));
        verify(cohosts).invite(EVENT, ALLY, 7L);
        assertThrows(ApiError.class, () -> call(HOST, "POST", "events/42/cohosts", DataObject.empty().put("guildId", Long.toString(STRANGER))), "only invited to the alliance");
        assertThrows(ApiError.class, () -> call(HOST, "POST", "events/42/cohosts", DataObject.empty().put("guildId", "999")), "not in the alliance at all");
        assertThrows(ApiError.class, () -> call(HOST, "POST", "events/42/cohosts", DataObject.empty().put("guildId", Long.toString(HOST))), "itself");
        verify(cohosts, never()).invite(EVENT, STRANGER, 7L);
    }

    @Test
    void anotherServerCannotUseTheHostRoutes() {
        assertThrows(ApiError.class, () -> call(ALLY, "GET", "events/42/cohosts", DataObject.empty()));
        assertThrows(ApiError.class, () -> call(ALLY, "POST", "events/42/cohosts", DataObject.empty().put("guildId", Long.toString(ALLY))));
    }

    @Test
    void coHostsCanOnlyBeRemovedBeforeSignupsClose() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.ACCEPTED, 7L, 8L, NOW, NOW)));
        when(cohosts.decide(EVENT, ALLY, Status.ACCEPTED, Status.REMOVED, 7L)).thenReturn(true);
        call(HOST, "DELETE", "events/42/cohosts/200", DataObject.empty());
        verify(cohosts).decide(EVENT, ALLY, Status.ACCEPTED, Status.REMOVED, 7L);
        EventRepository.Setup closed = setup(true, false);
        when(events.setup(EVENT)).thenReturn(closed);
        assertThrows(ApiError.class, () -> call(HOST, "DELETE", "events/42/cohosts/200", DataObject.empty()));
    }

    @Test
    void aStrangerSeesNothingOfTheEvent() {
        assertThrows(ApiError.class, () -> call(STRANGER, "POST", "cohost-events/42/accept", DataObject.empty()));
        assertThrows(ApiError.class, () -> call(STRANGER, "DELETE", "cohost-events/42/players/3", DataObject.empty()));
        when(cohosts.forGuild(STRANGER)).thenReturn(List.of());
        assertEquals(0, call(STRANGER, "GET", "cohost-events", DataObject.empty()).getArray("events").length());
    }

    @Test
    void anInvitedServerAcceptsButOnlyWhileStillAllied() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.INVITED, 7L, null, NOW, null)));
        when(cohosts.decide(EVENT, ALLY, Status.INVITED, Status.ACCEPTED, 7L)).thenReturn(true);
        when(cohosts.forGuild(ALLY)).thenReturn(List.of());
        call(ALLY, "POST", "cohost-events/42/accept", DataObject.empty());
        verify(cohosts).decide(EVENT, ALLY, Status.INVITED, Status.ACCEPTED, 7L);
        // the ally left the alliance before accepting
        when(alliances.membersOf(1)).thenReturn(List.of(new AllianceRepository.Membership(1, HOST, AllianceRules.Status.ACTIVE, null, NOW)));
        assertThrows(ApiError.class, () -> call(ALLY, "POST", "cohost-events/42/accept", DataObject.empty()));
    }

    @Test
    void anInvitedServerCannotRemovePlayers() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.INVITED, 7L, null, NOW, null)));
        assertThrows(ApiError.class, () -> call(ALLY, "DELETE", "cohost-events/42/players/2", DataObject.empty()));
        verify(service, never()).leave(anyLong(), anyLong(), anyLong());
    }

    @Test
    void anAcceptedCoHostSeesAndRemovesOnlyItsOwnPlayers() {
        when(cohosts.get(EVENT, ALLY)).thenReturn(Optional.of(new Cohost(EVENT, ALLY, Status.ACCEPTED, 7L, 8L, NOW, NOW)));
        when(cohosts.forGuild(ALLY)).thenReturn(List.of(new Cohost(EVENT, ALLY, Status.ACCEPTED, 7L, 8L, NOW, NOW)));
        when(events.participant(EVENT, 2)).thenReturn(Optional.of(player(2, ALLY)));
        when(events.participant(EVENT, 1)).thenReturn(Optional.of(player(1, HOST)));
        DataObject view = call(ALLY, "GET", "cohost-events", DataObject.empty());
        DataObject only = view.getArray("events").getObject(0);
        assertEquals(1, only.getArray("yours").length(), "only the player who joined through this server");
        assertEquals("2", only.getArray("yours").getObject(0).getString("userId"));
        assertEquals(3, only.getLong("players"), "the others only as a count");
        call(ALLY, "DELETE", "cohost-events/42/players/2", DataObject.empty());
        verify(service).leave(HOST, EVENT, 2);
        assertThrows(ApiError.class, () -> call(ALLY, "DELETE", "cohost-events/42/players/1", DataObject.empty()), "the host's player");
        EventRepository.Setup confirmed = setup(true, true);
        when(events.setup(EVENT)).thenReturn(confirmed);
        assertThrows(ApiError.class, () -> call(ALLY, "DELETE", "cohost-events/42/players/2", DataObject.empty()), "after the roster is confirmed");
    }
}
