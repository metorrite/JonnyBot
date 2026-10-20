package com.younglings.bot.automod;

import com.younglings.bot.automod.AutomodRules.Facts;
import com.younglings.bot.automod.AutomodRules.Rule;
import com.younglings.bot.automod.AutomodRules.RuleConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules added in phase two: images, newlines, stickers, spoilers, masked links, zalgo, phishing, and the per-rule response and log channel. */
class AutomodPhaseTwoTest {
    private static final long T0 = 1_000_000L;

    private static Map<Rule, RuleConfig> only(Rule rule, RuleConfig config) {
        Map<Rule, RuleConfig> map = AutomodRules.allDefaults();
        map.put(rule, config);
        return map;
    }

    private static RuleConfig on(Rule rule) {
        return AutomodRules.defaults(rule).withEnabled(true);
    }

    @Test
    void everyNewRuleIsOffByDefault() {
        for (Rule rule : Rule.values()) assertFalse(AutomodRules.defaults(rule).enabled(), rule.name());
        AutomodRules.History h = new AutomodRules.History();
        assertNull(AutomodRules.firstViolation(AutomodRules.allDefaults(), new Facts("a\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n||x|| ||y|| ||z|| ||w|| ||v||", 99, 99, 99, host -> true), h, T0));
    }

    @Test
    void imageSpamCountsImagesWithinTheWindowAcrossMessages() {
        Map<Rule, RuleConfig> cfg = only(Rule.IMAGE_SPAM, new RuleConfig(true, false, 4, 10, List.of()));
        AutomodRules.History h = new AutomodRules.History();
        assertNull(AutomodRules.firstViolation(cfg, new Facts("", 0, 2, 0, x -> false), h, T0));
        assertNull(AutomodRules.firstViolation(cfg, new Facts("", 0, 1, 0, x -> false), h, T0 + 2000));
        assertEquals(Rule.IMAGE_SPAM, AutomodRules.firstViolation(cfg, new Facts("", 0, 1, 0, x -> false), h, T0 + 4000), "the fourth image in ten seconds");
        // a message without images never trips it, however many came before
        assertNull(AutomodRules.firstViolation(cfg, new Facts("hello", 0, 0, 0, x -> false), h, T0 + 5000));
        // later, outside the window, it counts afresh
        assertNull(AutomodRules.firstViolation(cfg, new Facts("", 0, 1, 0, x -> false), h, T0 + 60_000));
    }

    @Test
    void stickerSpamCountsStickersWithinTheWindow() {
        Map<Rule, RuleConfig> cfg = only(Rule.STICKERS, new RuleConfig(true, false, 3, 10, List.of()));
        AutomodRules.History h = new AutomodRules.History();
        assertNull(AutomodRules.firstViolation(cfg, new Facts("", 0, 0, 1, x -> false), h, T0));
        assertNull(AutomodRules.firstViolation(cfg, new Facts("", 0, 0, 1, x -> false), h, T0 + 1000));
        assertEquals(Rule.STICKERS, AutomodRules.firstViolation(cfg, new Facts("", 0, 0, 1, x -> false), h, T0 + 2000));
    }

    @Test
    void newlineSpamAllowsUpToTheLimit() {
        assertEquals(3, AutomodRules.newlineCount("a\nb\nc\nd"));
        Map<Rule, RuleConfig> cfg = only(Rule.NEWLINES, new RuleConfig(true, false, 5, 0, List.of()));
        assertNull(AutomodRules.firstViolation(cfg, "1\n2\n3\n4\n5\n6", 0, new AutomodRules.History(), T0), "five line breaks is the limit");
        assertEquals(Rule.NEWLINES, AutomodRules.firstViolation(cfg, "1\n2\n3\n4\n5\n6\n7", 0, new AutomodRules.History(), T0));
    }

    @Test
    void spoilerTagsAreCounted() {
        assertEquals(2, AutomodRules.spoilerCount("||a|| and ||b c||"));
        assertEquals(0, AutomodRules.spoilerCount("a || b"));
        Map<Rule, RuleConfig> cfg = only(Rule.SPOILERS, new RuleConfig(true, false, 2, 0, List.of()));
        assertNull(AutomodRules.firstViolation(cfg, "||a|| ||b||", 0, new AutomodRules.History(), T0));
        assertEquals(Rule.SPOILERS, AutomodRules.firstViolation(cfg, "||a|| ||b|| ||c||", 0, new AutomodRules.History(), T0));
    }

    @Test
    void maskedLinksAreFoundUnlessTheWordsAreTheAddressOrTheSiteIsAllowed() {
        assertTrue(AutomodRules.hasMaskedLink("free nitro [click here](https://evil.example/x)", List.of()));
        assertTrue(AutomodRules.hasMaskedLink("[youtube.com](https://evil.example/x)", List.of()), "words that look like a different site are the classic trick");
        assertFalse(AutomodRules.hasMaskedLink("[https://example.com/a](https://example.com/a)", List.of()));
        assertFalse(AutomodRules.hasMaskedLink("[example.com/a](https://example.com/a)", List.of()));
        assertFalse(AutomodRules.hasMaskedLink("[our wiki](https://www.runescape.wiki/w/Goblin)", List.of("runescape.wiki")));
        assertFalse(AutomodRules.hasMaskedLink("a plain https://example.com link and (parentheses)", List.of()));
        assertFalse(AutomodRules.hasMaskedLink("[note] (not a link)", List.of()));
        assertEquals(Rule.MASKED_LINKS, AutomodRules.firstViolation(only(Rule.MASKED_LINKS, on(Rule.MASKED_LINKS)), "[hi](https://x.example)", 0, new AutomodRules.History(), T0));
    }

    @Test
    void zalgoNeedsAStackOfAccentsNotJustAccentedLetters() {
        assertFalse(AutomodRules.hasZalgo("café crème brûlée naïve", 4));
        assertFalse(AutomodRules.hasZalgo("ééé", 4), "one accent on each letter");
        assertTrue(AutomodRules.hasZalgo("h́̂̃̄ello", 4));
        assertFalse(AutomodRules.hasZalgo("h́̂̃ello", 4), "three is below the limit");
        assertEquals(Rule.ZALGO, AutomodRules.firstViolation(only(Rule.ZALGO, on(Rule.ZALGO)), "ź̂̃̄̅algo", 0, new AutomodRules.History(), T0));
    }

    @Test
    void phishingMatchesTheSiteAndItsSubdomainsButNotLookalikes() {
        Set<String> list = Set.of("nitro-full.com", "steamcommunnuty.com");
        assertTrue(AutomodRules.hasPhishingLink("get nitro https://nitro-full.com/claim", list::contains));
        assertTrue(AutomodRules.hasPhishingLink("visit login.nitro-full.com now", list::contains), "a subdomain of a listed site");
        assertTrue(AutomodRules.hasPhishingLink("NITRO-FULL.COM", list::contains));
        assertFalse(AutomodRules.hasPhishingLink("see https://steamcommunity.com/id/x", list::contains));
        assertFalse(AutomodRules.hasPhishingLink("not-nitro-full.com is another site", list::contains));
        assertFalse(AutomodRules.hasPhishingLink("hello world", list::contains));
        assertEquals(Rule.PHISHING, AutomodRules.firstViolation(only(Rule.PHISHING, on(Rule.PHISHING)), new Facts("http://nitro-full.com", 0, 0, 0, list::contains), new AutomodRules.History(), T0));
        assertNull(AutomodRules.firstViolation(only(Rule.PHISHING, on(Rule.PHISHING)), new Facts("http://fine.example", 0, 0, 0, list::contains), new AutomodRules.History(), T0));
    }

    @Test
    void anEmptyScamListFlagsNothing() {
        assertNull(AutomodRules.firstViolation(only(Rule.PHISHING, on(Rule.PHISHING)), new Facts("http://anything.example", 0, 0, 0, host -> false), new AutomodRules.History(), T0));
    }

    @Test
    void theScamListIsReadFromAJsonArrayAndOddEntriesAreSkipped() {
        Set<String> parsed = PhishingList.parse("[\"Nitro-Full.com\", \"bad entry\", 5, \"x\", \"a.b.example\", \"\"]");
        assertEquals(Set.of("nitro-full.com", "a.b.example"), parsed);
        assertThrows(RuntimeException.class, () -> PhishingList.parse("<html>rate limited</html>"));
        assertThrows(RuntimeException.class, () -> PhishingList.parse("{\"message\":\"nope\"}"));
    }

    @Test
    void aRuleWithNoResponseUsesTheStandardNoteAndACustomOneFillsInPlaceholders() {
        RuleConfig standard = on(Rule.CAPS);
        assertEquals("Please stop writing mostly in capitals, @drake.", standard.note(Rule.CAPS, "@drake"));
        RuleConfig custom = new RuleConfig(true, false, 70, 12, List.of(), "{user}: no shouting ({rule})", 0);
        assertEquals("@drake: no shouting (Too many capitals)", custom.note(Rule.CAPS, "@drake"));
    }

    @Test
    void aResponseHasALengthLimit() {
        RuleConfig tooLong = new RuleConfig(true, false, 70, 12, List.of(), "x".repeat(RuleConfig.MAX_RESPONSE + 1), 0);
        assertFalse(AutomodRules.problems(Rule.CAPS, tooLong).isEmpty());
        assertTrue(AutomodRules.problems(Rule.CAPS, new RuleConfig(true, false, 70, 12, List.of(), "x".repeat(RuleConfig.MAX_RESPONSE), 0)).isEmpty());
    }

    @Test
    void limitsOfTheNewRulesAreChecked() {
        assertFalse(AutomodRules.problems(Rule.IMAGE_SPAM, new RuleConfig(true, false, 1, 10, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.NEWLINES, new RuleConfig(true, false, 1, 0, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.STICKERS, new RuleConfig(true, false, 3, 0, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.SPOILERS, new RuleConfig(true, false, 0, 0, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.ZALGO, new RuleConfig(true, false, 1, 0, List.of())).isEmpty());
        assertFalse(AutomodRules.problems(Rule.MASKED_LINKS, new RuleConfig(true, false, 0, 0, List.of("not a site"))).isEmpty());
        for (Rule rule : Rule.values()) assertTrue(AutomodRules.problems(rule, AutomodRules.defaults(rule).withEnabled(rule != Rule.BLOCKED_WORDS)).isEmpty(), "defaults of " + rule);
    }

    @Test
    void settingsRoundTripThroughStorageIncludingResponseAndLogChannel() {
        Map<Rule, RuleConfig> rules = AutomodRules.allDefaults();
        rules.put(Rule.ZALGO, new RuleConfig(true, true, 5, 0, List.of(), "Keep it clean, {user}", 1234567890123456789L));
        Map<Rule, RuleConfig> back = AutomodRepository.parse(AutomodRepository.write(rules));
        assertEquals(rules.get(Rule.ZALGO), back.get(Rule.ZALGO));
        assertEquals(1234567890123456789L, back.get(Rule.ZALGO).logChannelId());
        for (Rule rule : Rule.values()) assertEquals(rules.get(rule), back.get(rule), rule.name());
    }

    @Test
    void settingsSavedBeforePhaseTwoStillLoadWithTheNewRulesOff() {
        String old = "{\"CAPS\":{\"enabled\":true,\"warn\":true,\"limit\":60,\"seconds\":10,\"words\":[]}}";
        Map<Rule, RuleConfig> parsed = AutomodRepository.parse(old);
        assertTrue(parsed.get(Rule.CAPS).enabled());
        assertEquals("", parsed.get(Rule.CAPS).response());
        assertEquals(0, parsed.get(Rule.CAPS).logChannelId());
        assertFalse(parsed.get(Rule.PHISHING).enabled());
        assertFalse(parsed.get(Rule.MASKED_LINKS).enabled());
    }
}
