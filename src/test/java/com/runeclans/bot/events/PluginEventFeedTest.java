package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.plugin.PluginEventType;
import com.runeclans.bot.plugin.PluginPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginEventFeedTest {
    private static final long USER = 7L;
    private static final Instant START = Instant.parse("2026-10-16T18:00:00Z");
    private static final Instant END = Instant.parse("2026-10-16T21:00:00Z");

    private EventRepository repository;
    private EventService service;
    private PluginEventFeed feed;

    @BeforeEach
    void setUp() {
        repository = mock(EventRepository.class);
        service = mock(EventService.class);
        BingoTiles tiles = mock(BingoTiles.class);
        when(tiles.resolve(anyString())).thenAnswer(i -> new BingoTiles.Ref(null, ((String) i.getArgument(0)).strip(), null));
        feed = new PluginEventFeed(repository, service, tiles);
    }

    private static Event bingo(long id, Instant starts, Instant ends) {
        return new Event(id, 3L, BingoTemplate.KEY, "Bingo", "", Status.RUNNING, Scope.PRIVATE, ScoringMode.INDIVIDUAL, starts, ends, 50, "{}", 1L, Instant.now(), null, false);
    }

    private static PluginPayload.Event drop(String key, Instant at, String item) {
        return new PluginPayload.Event(key, PluginEventType.DROP, at, null, null, null, "{\"itemName\":\"" + item + "\"}");
    }

    private void tileFor(long eventId, String item) {
        when(repository.objectives(eventId)).thenReturn(List.of(new ObjectiveSpec("t1", item, "", 5, Evidence.EITHER, 1, 1, List.of())));
    }

    @Test
    void aDropInsideTheEventCountsOnceUnderItsOwnKey() {
        when(repository.runningEventsForUser(USER)).thenReturn(List.of(bingo(11L, START, END)));
        tileFor(11L, "Torva boots");

        feed.accepted(USER, "Axley", List.of(drop("drop-1", Instant.parse("2026-10-16T19:00:00Z"), "Torva boots")));

        verify(service).recordProgress(eq(3L), eq(11L), eq("t1"), eq(USER), eq(1L), eq(Source.AUTO), eq("plugin:7:drop-1"), isNull(), anyString());
    }

    @Test
    void somethingThatHappenedBeforeOrAfterTheEventDoesNotCount() {
        when(repository.runningEventsForUser(USER)).thenReturn(List.of(bingo(11L, START, END)));
        tileFor(11L, "Torva boots");

        feed.accepted(USER, "Axley", List.of(drop("early", Instant.parse("2026-10-16T17:59:00Z"), "Torva boots"), drop("late", Instant.parse("2026-10-16T21:01:00Z"), "Torva boots")));

        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), anyString(), any(), anyString());
        assertTrue(PluginEventFeed.during(bingo(1L, null, null), Instant.now()), "an event with no times counts everything while it runs");
        assertFalse(PluginEventFeed.during(bingo(1L, START, END), null));
    }

    @Test
    void anEngineRefusalIsNotAnErrorAndTheNextEventStillGetsItsTurn() {
        when(repository.runningEventsForUser(USER)).thenReturn(List.of(bingo(11L, null, null), bingo(12L, null, null)));
        tileFor(11L, "Torva boots");
        tileFor(12L, "Torva boots");
        when(service.recordProgress(eq(3L), eq(11L), anyString(), anyLong(), anyLong(), any(), anyString(), any(), anyString())).thenThrow(new EventProblem(409, "That has already been counted."));

        assertDoesNotThrow(() -> feed.accepted(USER, "Axley", List.of(drop("drop-1", Instant.now(), "Torva boots"))));

        verify(service).recordProgress(eq(3L), eq(12L), eq("t1"), eq(USER), eq(1L), eq(Source.AUTO), eq("plugin:7:drop-1"), isNull(), anyString());
    }

    @Test
    void aFailureAnywhereNeverEscapesToTheUpload() {
        when(repository.runningEventsForUser(USER)).thenThrow(new RuntimeException("database down"));

        assertDoesNotThrow(() -> feed.accepted(USER, "Axley", List.of(drop("drop-1", Instant.now(), "Torva boots"))));
    }

    @Test
    void aPlayerInNoRunningEventCostsNothing() {
        when(repository.runningEventsForUser(USER)).thenReturn(List.of());

        feed.accepted(USER, "Axley", List.of(drop("drop-1", Instant.now(), "Torva boots")));

        verify(repository, never()).objectives(anyLong());
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }
}
