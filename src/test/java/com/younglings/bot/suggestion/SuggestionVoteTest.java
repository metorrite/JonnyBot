package com.younglings.bot.suggestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SuggestionVoteTest {
    @Test
    void firstPressCastsThatVote() {
        assertEquals(SuggestionVote.UP, SuggestionVote.next(SuggestionVote.NONE, SuggestionVote.UP));
        assertEquals(SuggestionVote.DOWN, SuggestionVote.next(SuggestionVote.NONE, SuggestionVote.DOWN));
    }

    @Test
    void pressingTheSameButtonAgainTakesTheVoteBack() {
        assertEquals(SuggestionVote.NONE, SuggestionVote.next(SuggestionVote.UP, SuggestionVote.UP));
        assertEquals(SuggestionVote.NONE, SuggestionVote.next(SuggestionVote.DOWN, SuggestionVote.DOWN));
    }

    @Test
    void pressingTheOtherButtonSwitchesTheVote() {
        assertEquals(SuggestionVote.DOWN, SuggestionVote.next(SuggestionVote.UP, SuggestionVote.DOWN));
        assertEquals(SuggestionVote.UP, SuggestionVote.next(SuggestionVote.DOWN, SuggestionVote.UP));
    }

    @Test
    void nothingIsNotAButton() {
        assertThrows(IllegalArgumentException.class, () -> SuggestionVote.next(SuggestionVote.UP, SuggestionVote.NONE));
    }

    @Test
    void buttonIdsRoundTrip() {
        assertEquals("sg:up:12", SuggestionVote.buttonId("up", 12));
        assertEquals(SuggestionVote.UP, SuggestionVote.directionOf("sg:up:12"));
        assertEquals(SuggestionVote.DOWN, SuggestionVote.directionOf("sg:down:12"));
        assertEquals(12, SuggestionVote.suggestionOf("sg:down:12"));
    }

    @Test
    void otherButtonsAreIgnored() {
        assertEquals(SuggestionVote.NONE, SuggestionVote.directionOf("gw:5"));
        assertEquals(-1, SuggestionVote.suggestionOf("gw:5"));
        assertEquals(-1, SuggestionVote.suggestionOf("sg:up:notanumber"));
    }
}
