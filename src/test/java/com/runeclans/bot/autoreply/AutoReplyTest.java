package com.runeclans.bot.autoreply;

import com.runeclans.bot.autoreply.AutoReply.Match;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoReplyTest {
    private static AutoReply reply(String trigger, Match match) {
        return new AutoReply(1L, 5L, trigger, match, "hi", 30, true);
    }

    @Test
    void wordMatchesAWholeWordAnywhereIgnoringCapitals() {
        AutoReply r = reply("hello", Match.WORD);

        assertTrue(r.matches("Hello"));
        assertTrue(r.matches("oh HELLO there"));
        assertTrue(r.matches("hello, world"));
    }

    @Test
    void wordDoesNotMatchInsideAnotherWord() {
        AutoReply r = reply("hello", Match.WORD);

        assertFalse(r.matches("helloooo"));
        assertFalse(r.matches("shellos"));
        assertFalse(r.matches("say hello2"));
    }

    @Test
    void wordCanBeAPhraseAndSpecialCharactersAreLiteral() {
        assertTrue(reply("what is a ca?", Match.WORD).matches("hey, what is a ca? thanks"));
        assertFalse(reply("a.b", Match.WORD).matches("axb"));
    }

    @Test
    void startsOnlyMatchesAtTheBeginning() {
        AutoReply r = reply("!rules", Match.STARTS);

        assertTrue(r.matches("!rules please"));
        assertFalse(r.matches("see !rules"));
    }

    @Test
    void exactNeedsTheWholeMessage() {
        AutoReply r = reply("gm", Match.EXACT);

        assertTrue(r.matches("GM"));
        assertTrue(r.matches("  gm  "));
        assertFalse(r.matches("gm all"));
    }

    @Test
    void emptyThingsNeverMatch() {
        assertFalse(reply("x", Match.WORD).matches(""));
        assertFalse(reply("x", Match.WORD).matches(null));
        assertFalse(reply(" ", Match.WORD).matches("anything"));
    }

    @Test
    void theReplyFillsInItsVariables() {
        assertEquals("Hi <@1>, welcome to Younglings", AutoReplyListener.fill("Hi {user}, welcome to {server}", "<@1>", "Younglings"));
    }
}
