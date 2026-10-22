package com.younglings.bot.plugin;

import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.events.EventService;
import com.younglings.bot.points.PointsRepository;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuneSyncPanelServiceTest {
    private static final long GUILD = 100L;
    private static final long ME = 7L;
    private static final long OTHER = 8L;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final RuneSyncRepository repository = mock(RuneSyncRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventService eventService = mock(EventService.class);
    private final PointsRepository points = mock(PointsRepository.class);
    private final RuneSyncPanelService service = new RuneSyncPanelService(repository, events, eventService, points);

    private static Event event(long id, String name, Status status) {
        return new Event(id, GUILD, "competition", name, "", status, Scope.PRIVATE, ScoringMode.INDIVIDUAL, NOW, NOW.plusSeconds(3600), 0, "{}", 1L, NOW, null, false);
    }

    private static Participant player(long eventId, long userId) {
        return new Participant(eventId, userId, GUILD, null, null, false, null, NOW, null, null, null, null, null, null);
    }

    @Test
    void aClanShowsItsEventsAndOnlyTheMembersOwnPointsAndProgress() {
        when(events.events(eq(GUILD), eq(List.of(Status.RUNNING, Status.SIGNUPS)), eq(RuneSyncPanelService.EVENTS_PER_CLAN)))
                .thenReturn(List.of(event(1, "Boss night", Status.RUNNING), event(2, "Skill week", Status.SIGNUPS), event(3, "Other running", Status.RUNNING)));
        when(events.participant(1, ME)).thenReturn(Optional.of(player(1, ME)));
        when(events.participant(2, ME)).thenReturn(Optional.empty());
        when(events.participant(3, ME)).thenReturn(Optional.empty());
        when(eventService.standings(GUILD, 1)).thenReturn(List.of(
                new Standing("u:" + OTHER, 90, 3, Map.of("a", 1, "b", 1, "c", 1), NOW),
                new Standing("u:" + ME, 40, 1, Map.of("a", 2), NOW)));
        when(eventService.objectives(1)).thenReturn(List.of(
                new ObjectiveSpec("a", "Kill a boss", "", 20, Evidence.AUTO, 1, 0, List.of()),
                new ObjectiveSpec("b", "Get a drop", "", 30, Evidence.AUTO, 1, 0, List.of())));
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, true, "points", null));
        when(points.balance(GUILD, ME)).thenReturn(1250L);

        DataObject clan = service.clan(GUILD, "Younglings", ME);

        assertEquals(3, clan.getArray("events").length());
        assertTrue(clan.getArray("events").getObject(0).getBoolean("joined"));
        assertFalse(clan.getArray("events").getObject(1).getBoolean("joined"));
        assertEquals(1250L, clan.getObject("points").getLong("balance"));
        assertEquals(1, clan.getArray("progress").length(), "progress only for running events the member is in");
        DataObject mine = clan.getArray("progress").getObject(0);
        assertEquals(40, mine.getLong("points"), "the member's own points, not the leader's");
        assertEquals(1, mine.getInt("objectivesDone"));
        assertEquals(2, mine.getInt("objectivesTotal"));
        assertFalse(clan.toString().contains(Long.toString(OTHER)), "no other player appears");
    }

    @Test
    void pointsAreLeftOutWhenTheClanHasThemOff() {
        when(events.events(eq(GUILD), eq(List.of(Status.RUNNING, Status.SIGNUPS)), eq(RuneSyncPanelService.EVENTS_PER_CLAN))).thenReturn(List.of());
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, false, "points", null));

        DataObject clan = service.clan(GUILD, "Younglings", ME);

        assertFalse(clan.getObject("points").getBoolean("enabled"));
        assertFalse(clan.getObject("points").hasKey("balance"));
    }

    @Test
    void withoutDiscordNoClanIsAvailable() {
        when(repository.clanPanelOn(anyLong())).thenReturn(true);
        assertFalse(service.available(ME));
        assertFalse(service.panel(ME, NOW).getBoolean("available"));
    }
}
