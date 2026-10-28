package com.runeclans.bot.afk;

import com.runeclans.bot.afk.AfkRepository.Afk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfkServiceTest {
    private AfkRepository repository;
    private AfkService service;

    @BeforeEach
    void setUp() {
        repository = mock(AfkRepository.class);
        when(repository.forGuild(1L)).thenReturn(List.of(new Afk(1L, 10L, "lunch", Instant.ofEpochSecond(1000))));
        service = new AfkService(repository);
    }

    @Test
    void whoIsAwayIsLoadedOncePerServer() {
        assertTrue(service.get(1L, 10L).isPresent());
        assertFalse(service.get(1L, 11L).isPresent());
        service.get(1L, 12L);

        verify(repository).forGuild(1L);
    }

    @Test
    void goingAwayIsRememberedAndSaved() {
        service.set(1L, 11L, "  gym  ");

        assertEquals("gym", service.get(1L, 11L).orElseThrow().reason());
        verify(repository).set(any());
    }

    @Test
    void aLongReasonIsCutShort() {
        service.set(1L, 11L, "x".repeat(500));

        assertEquals(AfkService.MAX_REASON, service.get(1L, 11L).orElseThrow().reason().length());
    }

    @Test
    void comingBackClearsItAndSaysSo() {
        assertTrue(service.clear(1L, 10L));
        assertFalse(service.get(1L, 10L).isPresent());
        verify(repository).clear(1L, 10L);
    }

    @Test
    void clearingSomeoneWhoWasNotAwayDoesNothing() {
        assertFalse(service.clear(1L, 99L));
        verify(repository, never()).clear(1L, 99L);
    }

    @Test
    void theLineNamesThemGivesTheReasonAndWhen() {
        assertEquals("**Sam** is away: lunch (since <t:1000:R>)", AfkService.line("Sam", new Afk(1L, 10L, "lunch", Instant.ofEpochSecond(1000))));
        assertEquals("**Sam** is away (since <t:1000:R>)", AfkService.line("Sam", new Afk(1L, 10L, "", Instant.ofEpochSecond(1000))));
    }
}
