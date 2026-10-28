package com.runeclans.bot.highlight;

import com.runeclans.bot.highlight.HighlightRepository.Keyword;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighlightTest {
    @Test
    void wholeWordIgnoresCaseAndLongerWords() {
        assertTrue(HighlightMatcher.matches("Anyone doing Zulrah tonight?", "zulrah", true));
        assertTrue(HighlightMatcher.matches("zulrah, anyone?", "ZULRAH", true));
        assertFalse(HighlightMatcher.matches("the zulrahs are back", "zulrah", true));
        assertFalse(HighlightMatcher.matches("nothing here", "zulrah", true));
    }

    @Test
    void containsMatchesInsideWordsAndTreatsSymbolsLiterally() {
        assertTrue(HighlightMatcher.matches("the zulrahs are back", "zulrah", false));
        assertTrue(HighlightMatcher.matches("price is 5.5m (ish)", "5.5m (ish)", true));
        assertFalse(HighlightMatcher.matches("price is 5x5m", "5.5m", false));
    }

    @Test
    void cleanCollapsesSpaces() {
        assertEquals("tombs of amascut", HighlightMatcher.clean("  tombs   of amascut "));
        assertEquals("", HighlightMatcher.clean(null));
    }

    @Test
    void cooldownAndAuthorAreRespected() {
        HighlightListener listener = new HighlightListener(null);
        HighlightConfig config = new HighlightConfig(1, true, 10, 60, Set.of(), Set.of());
        List<Keyword> words = List.of(new Keyword(1, 1, 10, "zulrah", true), new Keyword(2, 1, 20, "zulrah", true), new Keyword(3, 1, 10, "vorkath", true));

        Map<Long, List<String>> first = listener.matchesByMember(words, "zulrah and vorkath", 20, config, 1_000);
        assertEquals(Set.of(10L), first.keySet(), "the author (20) is never told about their own message");
        assertEquals(List.of("zulrah", "vorkath"), first.get(10L), "two words in one message share one entry, so one message");

        assertTrue(listener.matchesByMember(words, "zulrah", 99, config, 30_000).containsKey(20L) , "20 has not been told yet");
        assertFalse(listener.matchesByMember(words, "zulrah", 99, config, 31_000).containsKey(10L), "still resting 30s after");
        assertTrue(listener.matchesByMember(words, "zulrah", 99, config, 62_000).containsKey(10L), "rested for 60s");
    }
}
