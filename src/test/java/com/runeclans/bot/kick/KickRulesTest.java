package com.runeclans.bot.kick;

import com.runeclans.bot.kick.KickRules.OfflineAction;
import com.runeclans.bot.kick.KickRules.Step;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KickRulesTest {
    @Test
    void aLoginCanBeTypedAsANameOrAnAddress() {
        assertEquals(Optional.of("some_streamer"), KickRules.loginFrom("  Some_Streamer "));
        assertEquals(Optional.of("some_streamer"), KickRules.loginFrom("https://www.kick.com/Some_Streamer"));
        assertEquals(Optional.of("some_streamer"), KickRules.loginFrom("kick.com/some_streamer/videos?x=1"));
    }

    @Test
    void thingsThatCannotBeALoginAreRefused() {
        for (String bad : new String[]{"", "  ", "has space", "bad.dot", "x".repeat(26), "https://evil.example/streamer", null}) {
            assertTrue(KickRules.loginFrom(bad).isEmpty(), "[" + bad + "]");
        }
    }

    @Test
    void theGoLiveLineFillsInThePlaceholdersAndFallsBackToTheDefault() {
        assertEquals("Hey Bob playing Chess: https://t/bob", KickRules.render("Hey {streamer} playing {game}: {url}", "Bob", "Title", "Chess", "https://t/bob"));
        assertEquals("Bob is live on Kick!", KickRules.render("  ", "Bob", "Title", "Chess", "u"));
        assertEquals("Bob is live on Kick!", KickRules.render(null, "Bob", "Title", null, "u"));
    }

    @Test
    void thePreviewAddressGetsACacheBuster() {
        assertEquals("https://x/thumb.jpg?t=2", KickRules.thumbnail("https://x/thumb.jpg", 120_000));
        assertEquals("https://x/thumb.jpg?a=1&t=2", KickRules.thumbnail("https://x/thumb.jpg?a=1", 120_000));
        assertEquals(null, KickRules.thumbnail("", 1));
        assertEquals(null, KickRules.thumbnail("http://insecure/x.jpg", 1));
    }

    @Test
    void theFirstLookOnlyRecordsEvenForAStreamAlreadyRunning() {
        assertEquals(Step.PRIME, KickRules.decide(false, null, "s1", 0));
        assertEquals(Step.PRIME, KickRules.decide(false, null, null, 0));
    }

    @Test
    void aNewStreamIsAnnouncedOnceAndNotAgainWhileItRuns() {
        assertEquals(Step.ANNOUNCE, KickRules.decide(true, null, "s1", 0));
        assertEquals(Step.NOTHING, KickRules.decide(true, "s1", "s1", 0));
        assertEquals(Step.ANNOUNCE, KickRules.decide(true, "s1", "s2", 0), "a different stream is a new post");
    }

    @Test
    void aStreamIsOnlyOverAfterTwoPollsInARowFindThemOffline() {
        assertEquals(Step.COUNT_MISS, KickRules.decide(true, "s1", null, 0));
        assertEquals(Step.END, KickRules.decide(true, "s1", null, 1));
        assertEquals(Step.RESET_MISSES, KickRules.decide(true, "s1", "s1", 1), "back after a blip: the same post stays, nothing new is announced");
        assertEquals(Step.NOTHING, KickRules.decide(true, null, null, 0));
    }

    @Test
    void whatHappensWhenAStreamEndsCanBeReadBack() {
        assertEquals(Optional.of(OfflineAction.DELETE), OfflineAction.ofKey("delete"));
        assertTrue(OfflineAction.ofKey("explode").isEmpty());
    }
}
