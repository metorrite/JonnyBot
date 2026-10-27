package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.SkillWeekRepository.Settings;
import com.younglings.bot.events.SkillWeekRepository.Week;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Skill of the week (T-541): opening the week's competition from the rota, once, and only where it is switched on. */
class SkillWeekServiceTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ADMIN = Long.parseLong("5".repeat(18));
    private static final Instant NOW = Instant.parse("2026-10-27T13:00:00Z");
    private static final Instant MONDAY = Instant.parse("2026-10-26T00:00:00Z");

    private final SkillWeekRepository weeks = Mockito.mock(SkillWeekRepository.class);
    private final EventService events = Mockito.mock(EventService.class);
    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final PlayerLinkService links = Mockito.mock(PlayerLinkService.class);
    private final MemberProfileRepository profiles = Mockito.mock(MemberProfileRepository.class);
    private final SkillWeekService service = new SkillWeekService(weeks, events, repository, Mockito.mock(PluginXpTracker.class), hub, links, profiles)
            .withClock(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void opensTheNextSkillOfTheRotaAsAWholeWeekRuneSyncCompetition() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9, 15, 25), 4, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY, 15)).thenReturn(true);
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenReturn(42L);

        assertEquals(OptionalLong.of(42), service.open(GUILD, NOW));
        ArgumentCaptor<EventService.EventInput> input = ArgumentCaptor.forClass(EventService.EventInput.class);
        verify(events).createWholeSpanEvent(eq(GUILD), eq(ADMIN), input.capture());
        assertEquals(CompetitionTemplate.KEY, input.getValue().template());
        assertEquals(MONDAY, input.getValue().startsAt());
        assertEquals(Instant.parse("2026-11-01T23:59:59Z"), input.getValue().endsAt());
        DataObject config = input.getValue().config();
        assertEquals(15, CompetitionTemplate.skillOf(config));
        assertTrue(CompetitionTemplate.countsFromPlugin(config));
        assertTrue(CompetitionTemplate.countsWholeSpan(config));
        verify(weeks).setEvent(GUILD, MONDAY, 42);
        verify(events).moveStage(GUILD, 42, Status.RUNNING);
    }

    @Test
    void offAnEmptyRotaOrAnOpenWeekOpensNothing() {
        when(weeks.settings(GUILD)).thenReturn(Settings.OFF);
        assertTrue(service.open(GUILD, NOW).isEmpty());
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(), 0, ADMIN));
        assertTrue(service.open(GUILD, NOW).isEmpty());
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 0, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.of(new Week(GUILD, MONDAY, 9, 1L, null, null)));
        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(weeks, never()).claim(anyLong(), any(), anyInt());
        verify(events, never()).createWholeSpanEvent(anyLong(), anyLong(), any());
    }

    @Test
    void aCompetitionThatCantBeMadeGivesTheWeekBackForTheNextPass() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 0, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY, 9)).thenReturn(true);
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenThrow(new EventProblem(409, "Too many unfinished events."));

        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(weeks).release(GUILD, MONDAY);
        verify(weeks, never()).setEvent(anyLong(), any(), anyLong());
    }

    @Test
    void aDatabaseFailureMakingItGivesTheWeekBackToo() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 0, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY, 9)).thenReturn(true);
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenThrow(new RuntimeException("database down"));

        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(weeks).release(GUILD, MONDAY);
    }

    @Test
    void aCompetitionTheWeekCantPointAtIsCancelledAndTheWeekGivenBack() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 0, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY, 9)).thenReturn(true);
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenReturn(42L);
        Mockito.doThrow(new RuntimeException("database down")).when(weeks).setEvent(GUILD, MONDAY, 42);

        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(events).moveStage(GUILD, 42, Status.CANCELLED);
        verify(weeks).release(GUILD, MONDAY);
        verify(events, never()).moveStage(GUILD, 42, Status.RUNNING);
    }

    @Test
    void aCompetitionThatDidntStartIsStartedOnTheNextPassAndCancelledIfTheWeekEndsFirst() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 1, ADMIN));
        Week week = new Week(GUILD, MONDAY, 9, 42L, null, null);
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.of(week));
        when(repository.event(GUILD, 42)).thenReturn(Optional.of(event(Status.DRAFT)));

        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(events).moveStage(GUILD, 42, Status.RUNNING);
        verify(weeks, never()).claim(anyLong(), any(), anyInt());

        when(weeks.close(eq(GUILD), eq(MONDAY), any())).thenReturn(true);
        assertEquals(List.of(), service.close(week).orElseThrow());
        verify(events).moveStage(GUILD, 42, Status.CANCELLED);
        verify(events, never()).moveStage(GUILD, 42, Status.DONE);
    }

    @Test
    void onlyRuneSyncsGainsCountNotAnOrganisersCorrection() {
        when(repository.progress(42)).thenReturn(List.of(progress(1, EventRules.Source.IMPORT, 5_000), progress(1, EventRules.Source.MANUAL, 90_000),
                progress(2, EventRules.Source.IMPORT, 7_000)));
        assertEquals(java.util.Map.of(1L, 5_000L, 2L, 7_000L), service.xpByUser(42));
    }

    private static EventRepository.Event event(Status status) {
        return new EventRepository.Event(42, GUILD, CompetitionTemplate.KEY, "Skill of the week", "", status, null, EventRules.ScoringMode.INDIVIDUAL, MONDAY,
                SkillWeekRules.weekEnd(MONDAY), 0, "{\"skillId\":9,\"xpSource\":\"plugin\",\"wholeSpan\":true}", ADMIN, MONDAY, null, false);
    }

    private static EventRepository.Progress progress(long userId, EventRules.Source source, long amount) {
        return new EventRepository.Progress(1, 42, CompetitionTemplate.OBJECTIVE_KEY, userId, null, amount, source, "k", null, null, MONDAY);
    }

    @Test
    void theSweepClosesOnlyWeeksThatAreOverAndOpensThisOne() {
        Week last = new Week(GUILD, MONDAY.minusSeconds(7 * 86_400), 9, null, null, null);
        Week current = new Week(GUILD, MONDAY, 15, null, null, null);
        when(weeks.unclosedBefore(any())).thenReturn(List.of(last, current));
        when(weeks.enabledGuilds()).thenReturn(List.of(GUILD));
        when(weeks.settings(GUILD)).thenReturn(Settings.OFF);
        when(weeks.close(eq(GUILD), any(), any())).thenReturn(true);

        service.sweep();
        verify(weeks).close(GUILD, last.start(), "[]");
        verify(weeks, never()).close(eq(GUILD), eq(MONDAY), any());
        assertFalse(service.available());
    }

    @Test
    void theClanWebsiteNamesNobodyWhoHidThemselvesFromTheRankings() {
        long shown = Long.parseLong("1".repeat(18));
        long hiddenName = Long.parseLong("2".repeat(18));
        long hiddenLink = Long.parseLong("3".repeat(18));
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 1, ADMIN));
        when(weeks.recent(GUILD, 4)).thenReturn(List.of(new Week(GUILD, MONDAY, 9, 42L, null, null),
                new Week(GUILD, MONDAY.minusSeconds(7 * 86_400), 9, 41L, MONDAY, "[{\"rank\":1,\"userId\":\"" + hiddenName + "\",\"xp\":900}]")));
        when(repository.progress(42)).thenReturn(List.of(progress(shown, EventRules.Source.IMPORT, 3_000), progress(hiddenName, EventRules.Source.IMPORT, 2_000),
                progress(hiddenLink, EventRules.Source.IMPORT, 1_000)));
        when(links.getLinksForUser(GUILD, shown)).thenReturn(List.of(link(shown, "Shown")));
        when(links.getLinksForUser(GUILD, hiddenName)).thenReturn(List.of(link(hiddenName, "Hidden Name")));
        when(links.getLinksForUser(GUILD, hiddenLink)).thenReturn(List.of(link(hiddenLink, "Hidden Link")));
        when(profiles.hiddenRsns(GUILD, true)).thenReturn(Set.of("hidden name"));
        when(profiles.hiddenDiscordUsers(GUILD)).thenReturn(Set.of(hiddenLink));

        DataObject site = service.publicView(GUILD, 10);
        assertEquals(List.of("Shown", "A member", "A member"), names(site.getObject("current").getArray("standings")));
        assertEquals(List.of("A member"), names(site.getObject("last").getArray("winners")));
        assertEquals(2_000, site.getObject("current").getArray("standings").getObject(1).getLong("xp"));

        DataObject dashboard = service.view(GUILD, 10);
        assertEquals(List.of("Shown", "Hidden Name", "Hidden Link"), names(dashboard.getObject("current").getArray("standings")), "organisers see who it is");
    }

    private static List<String> names(net.dv8tion.jda.api.utils.data.DataArray rows) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.length(); i++) out.add(rows.getObject(i).getString("name"));
        return out;
    }

    private static PlayerLink link(long userId, String rsn) {
        return new PlayerLink(1, GUILD, userId, rsn, "test", null, null);
    }

    @Test
    void aWeekLeftWithoutItsCompetitionByARestartIsGivenBackAndOpenedAgain() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 1, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.of(new Week(GUILD, MONDAY, 9, null, null, null)));
        // claimed only a moment ago: another pass may still be making it
        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(weeks).releaseStale(GUILD, MONDAY, NOW.minus(SkillWeekService.STALE_CLAIM));
        verify(weeks, never()).claim(anyLong(), any(), anyInt());

        when(weeks.releaseStale(GUILD, MONDAY, NOW.minus(SkillWeekService.STALE_CLAIM))).thenReturn(true);
        EventRepository.Event orphan = new EventRepository.Event(40, GUILD, CompetitionTemplate.KEY, SkillWeekRules.eventName(9, MONDAY), "", Status.DRAFT, null,
                EventRules.ScoringMode.INDIVIDUAL, MONDAY, SkillWeekRules.weekEnd(MONDAY), 0, "{}", ADMIN, MONDAY, null, false);
        when(repository.events(GUILD, List.of(Status.DRAFT), 50)).thenReturn(List.of(orphan));
        when(weeks.claim(GUILD, MONDAY, 9)).thenReturn(true);
        when(events.createWholeSpanEvent(eq(GUILD), eq(ADMIN), any())).thenReturn(42L);
        assertEquals(OptionalLong.of(42), service.open(GUILD, NOW));
        verify(events).moveStage(GUILD, 40, Status.CANCELLED);
        verify(weeks).setEvent(GUILD, MONDAY, 42);
    }

    @Test
    void aCompetitionThatCantBeFinishedYetLeavesTheWeekOpenForTheNextPass() {
        Week week = new Week(GUILD, MONDAY.minusSeconds(7 * 86_400), 9, 42L, null, null);
        EventRepository.Event scoring = new EventRepository.Event(42, GUILD, CompetitionTemplate.KEY, "Skill of the week", "", Status.SCORING, null,
                EventRules.ScoringMode.INDIVIDUAL, week.start(), SkillWeekRules.weekEnd(week.start()), 0, "{}", ADMIN, week.start(), null, false);
        when(repository.event(GUILD, 42)).thenReturn(Optional.of(scoring));
        when(repository.progress(42)).thenReturn(List.of(progress(1, EventRules.Source.IMPORT, 5_000)));
        Mockito.doThrow(new RuntimeException("database down")).when(events).moveStage(GUILD, 42, Status.DONE);

        assertTrue(service.close(week).isEmpty());
        verify(weeks, never()).close(anyLong(), any(), any());

        Mockito.reset(events);
        assertEquals(1, service.close(week).orElseThrow().size());
        verify(events).moveStage(GUILD, 42, Status.DONE);
        verify(weeks).close(eq(GUILD), eq(week.start()), any());
    }
}
