package com.runeclans.bot.events;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventNamingTest {
    @Test
    void aChannelNameIsTheEventsNameInDiscordsStyle() {
        assertEquals("christmas-bingo-general-channel", EventNaming.channel("{event} General Channel", Map.of("event", "Christmas Bingo"), "general"));
        assertEquals("drakes-bingo-staff", EventNaming.channel("{event} Staff", Map.of("event", "Drake's Bingo!"), "staff"));
        assertEquals("cafe-night-board", EventNaming.channel("{Event} Board", Map.of("event", "Café  Night"), "board"));
        assertEquals("boss-night-the-red-rangers", EventNaming.channel("{event} {team}", Map.of("event", "Boss Night", "team", "The Red Rangers"), "team"));
    }

    @Test
    void aNameWithNothingUsableFallsBackAndNeverComesOutEmpty() {
        assertEquals("general", EventNaming.channel("{event}", Map.of("event", "!!!"), "general"));
        assertEquals("event", EventNaming.channel("{event}", Map.of("event", "!!!"), "???"));
        assertEquals("", EventNaming.slug("   "));
    }

    @Test
    void aRoleKeepsItsWordsAsWrittenWithTidySpacing() {
        assertEquals("Christmas Bingo Participant", EventNaming.label("{event}   Participant", Map.of("event", "Christmas Bingo"), "x"));
        assertEquals("Event", EventNaming.label("   ", Map.of("event", "Anything"), "  "));
    }

    @Test
    void whatIsFilledInIsNeverReadAsAPatternAgain() {
        assertEquals("a {team} b", EventNaming.fill("a {event} b", Map.of("event", "{team}", "team", "NO")));
        assertEquals("{unknown} stays", EventNaming.fill("{unknown} stays", Map.of("event", "x")));
    }

    @Test
    void aNameIsCutToDiscordsLengthWithoutLeavingADashOnTheEnd() {
        String name = EventNaming.channel("{event}", Map.of("event", "word ".repeat(40)), "x");
        assertTrue(name.length() <= EventNaming.MAX_LENGTH, name);
        assertTrue(!name.endsWith("-"), name);
        String label = EventNaming.label("{event}", Map.of("event", "word ".repeat(40)), "x");
        assertTrue(label.length() <= EventNaming.MAX_LENGTH, label);
    }

    @Test
    void patternsAreCheckedForVariablesAndContent() {
        assertTrue(EventNaming.problems("{event} General", Set.of("event"), "general chat").isEmpty());
        assertEquals(List.of("The general chat name can't use {evnt}; use {event}."), EventNaming.problems("{evnt} General", Set.of("event"), "general chat"));
        assertEquals(List.of("Give the staff channel a name pattern."), EventNaming.problems("   ", Set.of("event"), "staff channel"));
        assertEquals(1, EventNaming.problems("{event}{team}", Set.of("event"), "x").size());
        assertEquals(1, EventNaming.problems("x".repeat(101), Set.of("event"), "x").size());
        assertEquals(1, EventNaming.problems("{}", Set.of("event"), "x").stream().filter(p -> p.contains("some words")).count());
    }

    @Test
    void namesThatClashAreMadeDifferent() {
        assertEquals(List.of("red", "red-2", "blue", "red-3"), EventNaming.unique(List.of("red", "red", "blue", "red"), "-"));
        assertEquals(List.of("Red Team", "red team 2"), EventNaming.unique(List.of("Red Team", "red team"), " "));
        List<String> long100 = EventNaming.unique(List.of("a".repeat(100), "a".repeat(100)), "-");
        assertEquals(100, long100.get(1).length());
        assertTrue(long100.get(1).endsWith("-2"));
    }
}
