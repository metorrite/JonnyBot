package com.runeclans.bot.twitch;

import com.runeclans.bot.twitch.TwitchRules.OfflineAction;
import com.runeclans.bot.twitch.TwitchRules.Step;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwitchRulesTest {
    @Test
    void aLoginCanBeTypedAsANameOrAnAddress() {
        assertEquals(Optional.of("some_streamer"), TwitchRules.loginFrom("  Some_Streamer "));
        assertEquals(Optional.of("some_streamer"), TwitchRules.loginFrom("https://www.twitch.tv/Some_Streamer"));
        assertEquals(Optional.of("some_streamer"), TwitchRules.loginFrom("twitch.tv/some_streamer/videos?x=1"));
    }

    @Test
    void thingsThatCannotBeALoginAreRefused() {
        for (String bad : new String[]{"", "  ", "ab", "has space", "bad-dash", "x".repeat(26), "https://evil.example/streamer", null}) {
            assertTrue(TwitchRules.loginFrom(bad).isEmpty(), "[" + bad + "]");
        }
    }

    @Test
    void theGoLiveLineFillsInThePlaceholdersAndFallsBackToTheDefault() {
        assertEquals("Hey Bob playing Chess: https://t/bob", TwitchRules.render("Hey {streamer} playing {game}: {url}", "Bob", "Title", "Chess", "https://t/bob"));
        assertEquals("Bob is live on Twitch!", TwitchRules.render("  ", "Bob", "Title", "Chess", "u"));
        assertEquals("Bob is live on Twitch!", TwitchRules.render(null, "Bob", "Title", null, "u"));
    }

    @Test
    void thePreviewAddressGetsItsSizeAndACacheBuster() {
        assertEquals("https://x/live_user-{width}x{height}.jpg".replace("{width}", "440").replace("{height}", "248") + "?t=2", TwitchRules.thumbnail("https://x/live_user-{width}x{height}.jpg", 440, 248, 120_000));
        assertEquals(null, TwitchRules.thumbnail("", 440, 248, 1));
    }

    @Test
    void theFirstLookOnlyRecordsEvenForAStreamAlreadyRunning() {
        assertEquals(Step.PRIME, TwitchRules.decide(false, null, "s1", 0));
        assertEquals(Step.PRIME, TwitchRules.decide(false, null, null, 0));
    }

    @Test
    void aNewStreamIsAnnouncedOnceAndNotAgainWhileItRuns() {
        assertEquals(Step.ANNOUNCE, TwitchRules.decide(true, null, "s1", 0));
        assertEquals(Step.NOTHING, TwitchRules.decide(true, "s1", "s1", 0));
        assertEquals(Step.ANNOUNCE, TwitchRules.decide(true, "s1", "s2", 0), "a different stream is a new post");
    }

    @Test
    void aStreamIsOnlyOverAfterTwoPollsInARowFindThemOffline() {
        assertEquals(Step.COUNT_MISS, TwitchRules.decide(true, "s1", null, 0));
        assertEquals(Step.END, TwitchRules.decide(true, "s1", null, 1));
        assertEquals(Step.RESET_MISSES, TwitchRules.decide(true, "s1", "s1", 1), "back after a blip: the same post stays, nothing new is announced");
        assertEquals(Step.NOTHING, TwitchRules.decide(true, null, null, 0));
    }

    @Test
    void whatHappensWhenAStreamEndsCanBeReadBack() {
        assertEquals(Optional.of(OfflineAction.DELETE), OfflineAction.ofKey("delete"));
        assertTrue(OfflineAction.ofKey("explode").isEmpty());
    }
}
