package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Progress;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Competitions counted from RuneSync (#426) on the edges: a big roster is split into the hub's call size, a failing later call stops the pass
 * without saying who counts, waiting-list players and players not counting yet are never asked about, a lower reading takes nothing back,
 * "confirmed links only" means nothing outside a RuneSync competition, and the sweep forgets events that stopped running.
 */
class PluginXpTrackerEdgeCasesTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final Instant STARTED = Instant.parse("2026-10-20T18:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T19:00:30Z");

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService service = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final PluginXpTracker tracker = new PluginXpTracker(repository, service, hub).withClock(Clock.fixed(NOW, ZoneOffset.UTC));

    private static Event event(long id, String config) {
        return new Event(id, GUILD, CompetitionTemplate.KEY, "Comp", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, STARTED.minusSeconds(3600), NOW.plusSeconds(86_400), 0, config, 1,
                STARTED.minusSeconds(7200), null, false);
    }

    private static Participant player(long userId, Instant joined, boolean waiting) {
        return new Participant(1, userId, GUILD, null, null, waiting, null, joined, null, null, null, null, null, null);
    }

    /** A made-up user id per index, built at runtime (never a real Discord id). */
    private static long user(int i) {
        return Long.parseLong("5" + String.format("%017d", i));
    }

    private static RuneClansHubClient.Answer gains(List<String> aliases, long xp) {
        DataArray players = DataArray.empty();
        for (String alias : aliases) players.add(DataObject.empty().put("alias", alias).put("xp", xp));
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players));
    }

    private void roster(long eventId, List<Participant> players) {
        when(repository.startedAt(eventId)).thenReturn(Optional.of(STARTED));
        when(repository.participants(eventId)).thenReturn(players);
        when(repository.progress(eventId)).thenReturn(List.of());
        for (Participant p : players) when(hub.aliasOf(p.userId())).thenReturn(Optional.of("a_" + p.userId()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRosterBiggerThanOneHubCallIsSplitIntoCallsOfAtMost500() {
        int size = PluginXpTracker.MAX_PLAYERS_PER_CALL * 2 + 1;
        List<Participant> players = new ArrayList<>();
        for (int i = 0; i < size; i++) players.add(player(user(i), STARTED.minusSeconds(60), false));
        roster(1, players);
        when(hub.xpGains(eq(GUILD), any(Collection.class), any(), eq(STARTED), eq(NOW)))
                .thenAnswer(inv -> gains(List.copyOf((Collection<String>) inv.getArgument(1)), 10));

        assertEquals(size, tracker.feed(event(1, "{\"xpSource\":\"plugin\"}")));
        ArgumentCaptor<Collection<String>> calls = ArgumentCaptor.forClass(Collection.class);
        verify(hub, times(3)).xpGains(eq(GUILD), calls.capture(), any(), eq(STARTED), eq(NOW));
        assertEquals(List.of(500, 500, 1), calls.getAllValues().stream().map(Collection::size).toList());
        assertEquals(Set.of(), tracker.notCounted(1).orElseThrow().userIds());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aLaterCallFailingStopsThePassAndKeepsTheLastAnswerAboutWhoCounts() {
        List<Participant> players = new ArrayList<>();
        for (int i = 0; i < PluginXpTracker.MAX_PLAYERS_PER_CALL + 1; i++) players.add(player(user(i), STARTED.minusSeconds(60), false));
        roster(2, players);
        when(hub.xpGains(eq(GUILD), any(Collection.class), any(), any(), any()))
                .thenAnswer(inv -> gains(List.copyOf((Collection<String>) inv.getArgument(1)), 10))
                .thenReturn(new RuneClansHubClient.Answer(503, null));

        assertEquals(PluginXpTracker.MAX_PLAYERS_PER_CALL, tracker.feed(event(2, "{\"xpSource\":\"plugin\"}")), "the first call's players still count");
        assertTrue(tracker.notCounted(2).isEmpty(), "a half-answered pass says nothing about who the hub can't count");
    }

    @Test
    void waitingListPlayersArentAskedAboutAndAPlayerWhoHasntStartedCountingIsntCalledFor() {
        long waiting = user(1);
        long early = user(2);
        long future = user(3);
        roster(3, List.of(player(waiting, STARTED.minusSeconds(60), true), player(early, STARTED.minusSeconds(60), false),
                player(future, NOW.plusSeconds(60), false)));
        when(hub.xpGains(eq(GUILD), any(), any(), eq(STARTED), eq(NOW))).thenReturn(gains(List.of("a_" + early), 5));

        assertEquals(1, tracker.feed(event(3, "{\"xpSource\":\"plugin\"}")));
        verify(hub, never()).aliasOf(waiting);
        verify(hub, times(1)).xpGains(anyLong(), any(), any(), any(), any());
        verify(hub, never()).xpGains(anyLong(), any(), any(), eq(NOW.plusSeconds(60)), any());
        assertFalse(tracker.notCounted(3).orElseThrow().userIds().contains(future), "not counting yet isn't the same as not countable");
    }

    @Test
    void aLowerReadingThanWhatIsAlreadyCountedAddsNothingAndTakesNothingBack() {
        long alice = user(1);
        roster(4, List.of(player(alice, STARTED.minusSeconds(60), false)));
        when(repository.progress(4)).thenReturn(List.of(new Progress(1, 4, CompetitionTemplate.OBJECTIVE_KEY, alice, null, 5_000, Source.IMPORT, "pxp:x", null, null, STARTED)));
        when(hub.xpGains(eq(GUILD), any(), any(), any(), any())).thenReturn(gains(List.of("a_" + alice), 4_000));

        assertEquals(0, tracker.feed(event(4, "{\"xpSource\":\"plugin\"}")));
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    void progressTheEventRefusesIsntCountedAsAnUpdate() {
        long alice = user(1);
        roster(5, List.of(player(alice, STARTED.minusSeconds(60), false)));
        when(hub.xpGains(eq(GUILD), any(), any(), any(), any())).thenReturn(gains(List.of("a_" + alice), 4_000));
        doThrow(new EventProblem(409, "already complete")).when(service)
                .recordProgress(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), anyString(), any(), anyString());

        assertEquals(0, tracker.feed(event(5, "{\"xpSource\":\"plugin\"}")));
    }

    @Test
    void confirmedLinksOnlyMeansNothingOutsideARuneSyncCompetition() {
        assertFalse(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"xpSource\":\"auto\",\"confirmedLinksOnly\":true}")));
        assertFalse(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"xpSource\":\"runemetrics\",\"confirmedLinksOnly\":true}")));
        assertFalse(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"xpSource\":\"plugin\",\"confirmedLinksOnly\":false}")));
        assertFalse(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"xpSource\":\"plugin\",\"confirmedLinksOnly\":null}")));
        assertTrue(CompetitionTemplate.confirmedLinksOnly(DataObject.fromJson("{\"xpSource\":\"plugin\",\"confirmedLinksOnly\":\"true\"}")));
    }

    @Test
    void onlyTheHubsTwoConfirmedLevelsCountAsConfirmed() {
        assertTrue(PluginXpTracker.confirmed(DataObject.empty().put("verification", "public_data_corroborated")));
        assertTrue(PluginXpTracker.confirmed(DataObject.empty().put("verification", "jagex_signed")));
        assertFalse(PluginXpTracker.confirmed(DataObject.empty().put("verification", "implicit")));
        assertFalse(PluginXpTracker.confirmed(DataObject.empty().put("verification", "PUBLIC_DATA_CORROBORATED")), "an exact match, nothing looser");
        assertFalse(PluginXpTracker.confirmed(DataObject.empty().put("verification", "public_data_pending")), "a level that only starts like a confirmed one");
        assertFalse(PluginXpTracker.confirmed(DataObject.empty().put("verification", "not_jagex_signed")), "a level that only ends like a confirmed one");
        assertFalse(PluginXpTracker.confirmed(DataObject.empty()), "an older hub that doesn't say: not confirmed");
    }

    @Test
    void oneCompetitionFailingDoesntStopTheNextAndStoppedOnesAreForgotten() {
        long alice = user(1);
        Event broken = event(6, "{\"xpSource\":\"plugin\"}");
        Event fine = event(7, "{\"xpSource\":\"plugin\"}");
        when(repository.runningEvents(ClanVsClan.KEY)).thenReturn(List.of());
        when(repository.runningEvents(CompetitionTemplate.KEY)).thenReturn(List.of(broken, fine));
        when(repository.startedAt(6)).thenThrow(new IllegalStateException("database hiccup"));
        roster(7, List.of(player(alice, STARTED.minusSeconds(60), false)));
        when(hub.xpGains(eq(GUILD), any(), any(), any(), any())).thenReturn(gains(List.of(), 0));

        tracker.sweep();
        assertTrue(tracker.notCounted(7).isPresent(), "the second competition was still fed");
        assertEquals(Set.of(alice), tracker.notCounted(7).orElseThrow().userIds());

        when(repository.runningEvents(CompetitionTemplate.KEY)).thenReturn(List.of());
        tracker.sweep();
        assertTrue(tracker.notCounted(7).isEmpty(), "an event that stopped running is forgotten");
    }
}
