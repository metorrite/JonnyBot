package com.runeclans.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventWelcomeTest {
    @Test
    void noEventHasWelcomeMessagesUntilSomeoneWritesOne() {
        assertTrue(EventWelcome.of(DataObject.empty()).isEmpty());
        assertTrue(EventWelcome.of(DataObject.fromJson("{\"channels\":{\"enabled\":true}}")).isEmpty());
        assertTrue(EventWelcome.of(DataObject.fromJson("{\"channels\":{\"welcome\":{\"general\":\"   \"}}}")).isEmpty(), "blank is none");
        assertEquals(Map.of("general", "Hi {player}!"), EventWelcome.of(DataObject.fromJson("{\"channels\":{\"welcome\":{\"general\":\" Hi {player}! \",\"staff\":\"x\"}}}")),
                "trimmed; kinds that can't have one are ignored");
    }

    @Test
    void onlyTheFourKindsAndShortTextsAreAccepted() {
        assertTrue(EventWelcome.problems(DataObject.fromJson("{\"channels\":{\"welcome\":{\"announcements\":\"a\",\"general\":\"b\",\"teamChannel\":\"c\",\"playerChannel\":\"d\"}}}")).isEmpty());
        assertFalse(EventWelcome.problems(DataObject.fromJson("{\"channels\":{\"welcome\":{\"staff\":\"x\"}}}")).isEmpty());
        assertFalse(EventWelcome.problems(DataObject.fromJson("{\"channels\":{\"welcome\":{\"general\":5}}}")).isEmpty());
        assertFalse(EventWelcome.problems(DataObject.fromJson("{\"channels\":{\"welcome\":{\"general\":\"" + "x".repeat(EventWelcome.MAX_TEXT + 1) + "\"}}}")).isEmpty());
        // the channel settings check includes it, so a bad welcome can't be saved
        assertFalse(EventSpace.problems(DataObject.fromJson("{\"channels\":{\"welcome\":{\"staff\":\"x\"}}}")).isEmpty());
    }

    @Test
    void theWordsAreFilledIn() {
        Instant start = Instant.parse("2026-12-01T18:00:00Z");
        String out = EventWelcome.render("Welcome {player} of {team} to {event}! Starts {start}. Rules: {rules-link}", "Christmas Bingo", "Red", "Zezima", start,
                "https://rsyounglings.com/clan-events/12");
        assertEquals("Welcome Zezima of Red to Christmas Bingo! Starts <t:" + start.getEpochSecond() + ":F>. Rules: https://rsyounglings.com/clan-events/12", out);
        assertEquals("Starts not set yet, rules: ", EventWelcome.render("Starts {start}, rules: {rules-link}", "E", null, null, null, null));
        assertEquals(2000, EventWelcome.render("{event}".repeat(400), "abcdefghij", null, null, null, null).length(), "never longer than Discord allows");
    }
}
