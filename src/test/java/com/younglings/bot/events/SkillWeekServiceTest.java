package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.SkillWeekRepository.Settings;
import com.younglings.bot.events.SkillWeekRepository.Week;
import com.younglings.bot.runeclans.RuneClansHubClient;
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
    private final SkillWeekService service = new SkillWeekService(weeks, events, repository, Mockito.mock(PluginXpTracker.class), hub, Mockito.mock(PlayerLinkService.class))
            .withClock(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void opensTheNextSkillOfTheRotaAsAWholeWeekRuneSyncCompetition() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9, 15, 25), 4, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY, 15)).thenReturn(true);
        when(events.createEvent(eq(GUILD), eq(ADMIN), any())).thenReturn(42L);

        assertEquals(OptionalLong.of(42), service.open(GUILD, NOW));
        ArgumentCaptor<EventService.EventInput> input = ArgumentCaptor.forClass(EventService.EventInput.class);
        verify(events).createEvent(eq(GUILD), eq(ADMIN), input.capture());
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
        verify(events, never()).createEvent(anyLong(), anyLong(), any());
    }

    @Test
    void aCompetitionThatCantBeMadeGivesTheWeekBackForTheNextPass() {
        when(weeks.settings(GUILD)).thenReturn(new Settings(true, List.of(9), 0, ADMIN));
        when(weeks.week(GUILD, MONDAY)).thenReturn(Optional.empty());
        when(weeks.claim(GUILD, MONDAY, 9)).thenReturn(true);
        when(events.createEvent(eq(GUILD), eq(ADMIN), any())).thenThrow(new EventProblem(409, "Too many unfinished events."));

        assertTrue(service.open(GUILD, NOW).isEmpty());
        verify(weeks).release(GUILD, MONDAY);
        verify(weeks, never()).setEvent(anyLong(), any(), anyLong());
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
}
