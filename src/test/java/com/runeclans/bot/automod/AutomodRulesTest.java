package com.runeclans.bot.automod;

import com.runeclans.bot.automod.AutomodRules.Rule;
import com.runeclans.bot.automod.AutomodRules.RuleConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomodRulesTest {
    private static Map<Rule, RuleConfig> only(Rule rule, RuleConfig config) {
        Map<Rule, RuleConfig> map = AutomodRules.allDefaults();
        map.put(rule, config);
        return map;
    }

    private static RuleConfig on(Rule rule) {
        return AutomodRules.defaults(rule).withEnabled(true);
    }

    @Test
    void invitesAreRecognisedInTheirCommonForms() {
        assertTrue(AutomodRules.hasInvite("join discord.gg/abc123 now"));
        assertTrue(AutomodRules.hasInvite("https://discord.com/invite/abc-123"));
        assertTrue(AutomodRules.hasInvite("DISCORDAPP.COM/invite/xyz"));
        assertFalse(AutomodRules.hasInvite("I love discord gg"));
        assertFalse(AutomodRules.hasInvite("https://example.com/invite/me"));
    }

    @Test
    void linksAreCheckedAgainstTheAllowedWebsites() {
        List<String> allowed = List.of("runescape.com", "youtube.com");
        assertTrue(AutomodRules.hasDisallowedLink("look http://evil.example/x", allowed));
        assertFalse(AutomodRules.hasDisallowedLink("see https://www.runescape.com/news", allowed));
        assertFalse(AutomodRules.hasDisallowedLink("https://secure.runescape.com:443/a", allowed));
        assertTrue(AutomodRules.hasDisallowedLink("https://notrunescape.com/", allowed), "a lookalike domain is not a subdomain");
        assertFalse(AutomodRules.hasDisallowedLink("no links here", allowed));
        assertFalse(AutomodRules.hasDisallowedLink("discord.gg/abc is left to the invite rule", List.of()), "an invite without a scheme is not a URL");
        assertFalse(AutomodRules.hasDisallowedLink("https://discord.gg/abc", List.of()), "invites are left to their own rule");
    }

    @Test
    void blockedWordsMatchWholeWordsOnly() {
        List<String> words = List.of("ass", "noob");
        assertTrue(AutomodRules.hasBlockedWord("you ASS", words));
        assertTrue(AutomodRules.hasBlockedWord("such a noob!", words));
        assertFalse(AutomodRules.hasBlockedWord("a class act", words));
        assertFalse(AutomodRules.hasBlockedWord("assassin", words));
    }

    @Test
    void shoutingNeedsEnoughLettersAndEnoughCapitals() {
        assertTrue(AutomodRules.isShouting("STOP DOING THAT RIGHT NOW", 70, 12));
        assertFalse(AutomodRules.isShouting("OK", 70, 12), "too short to count");
        assertFalse(AutomodRules.isShouting("This is a normal sentence about RuneScape", 70, 12));
        assertFalse(AutomodRules.isShouting("1234567890 !!!!", 70, 12), "no letters");
    }

    @Test
    void emojiAreCountedUnicodeAndCustom() {
        assertEquals(3, AutomodRules.emojiCount("😀😀 <:wave:123456> hi"));
        assertEquals(0, AutomodRules.emojiCount("plain text"));
    }

    @Test
    void disabledRulesAreSkippedAndTheFirstBrokenRuleIsReported() {
        Map<Rule, RuleConfig> config = new EnumMap<>(AutomodRules.allDefaults());
        assertNull(AutomodRules.firstViolation(config, "discord.gg/abc", 0, new AutomodRules.History(), 1000));
        config.put(Rule.INVITES, on(Rule.INVITES));
        assertEquals(Rule.INVITES, AutomodRules.firstViolation(config, "discord.gg/abc", 0, new AutomodRules.History(), 1000));
        assertNull(AutomodRules.firstViolation(config, "hello there", 0, new AutomodRules.History(), 1000));
    }

    @Test
    void mentionsAboveTheLimitAreCaught() {
        Map<Rule, RuleConfig> config = only(Rule.MENTIONS, on(Rule.MENTIONS));
        assertNull(AutomodRules.firstViolation(config, "hi", 5, new AutomodRules.History(), 1000));
        assertEquals(Rule.MENTIONS, AutomodRules.firstViolation(config, "hi", 6, new AutomodRules.History(), 1000));
    }

    @Test
    void fastSpamCountsMessagesInTheWindow() {
        Map<Rule, RuleConfig> config = only(Rule.SPAM, on(Rule.SPAM)); // 6 messages in 5 seconds
        var history = new AutomodRules.History();
        for (int i = 0; i < 5; i++) assertNull(AutomodRules.firstViolation(config, "m" + i, 0, history, 1000L + i * 100));
        assertEquals(Rule.SPAM, AutomodRules.firstViolation(config, "m5", 0, history, 1600));
        var slow = new AutomodRules.History();
        for (int i = 0; i < 10; i++) assertNull(AutomodRules.firstViolation(config, "m" + i, 0, slow, 1000L + i * 2000));
    }

    @Test
    void repeatsNeedTheSameMessageAgainAndAgain() {
        Map<Rule, RuleConfig> config = only(Rule.REPEAT, on(Rule.REPEAT)); // 3 identical in 30 seconds
        var history = new AutomodRules.History();
        assertNull(AutomodRules.firstViolation(config, "Buy my gold", 0, history, 1000));
        assertNull(AutomodRules.firstViolation(config, "buy  my GOLD", 0, history, 2000));
        assertEquals(Rule.REPEAT, AutomodRules.firstViolation(config, "buy my gold", 0, history, 3000));
        var short1 = new AutomodRules.History();
        for (int i = 0; i < 5; i++) assertNull(AutomodRules.firstViolation(config, "ok", 0, short1, 1000L + i), "very short messages are not repeats");
    }

    @Test
    void settingsAreValidatedAndListsCleaned() {
        assertTrue(AutomodRules.problems(Rule.CAPS, AutomodRules.defaults(Rule.CAPS)).isEmpty());
        assertFalse(AutomodRules.problems(Rule.CAPS, new RuleConfig(true, false, 10, 12, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.BLOCKED_WORDS, new RuleConfig(true, false, 0, 0, List.of())).isEmpty(), "enabled needs a word");
        assertTrue(AutomodRules.problems(Rule.BLOCKED_WORDS, new RuleConfig(false, false, 0, 0, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.LINKS, new RuleConfig(true, false, 0, 0, List.of("not a site"))).isEmpty());
        assertTrue(AutomodRules.problems(Rule.LINKS, new RuleConfig(true, false, 0, 0, List.of("example.com"))).isEmpty());
        assertEquals(List.of("one", "two"), AutomodRules.cleanList(" One, two\none,, "));
    }
}
