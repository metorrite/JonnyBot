package com.runeclans.bot.autodelete;

import com.runeclans.bot.autodelete.AutoDeleteRule.Filter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoDeleteRuleTest {
    private static AutoDeleteRule rule(Filter... filters) {
        var set = EnumSet.noneOf(Filter.class);
        for (Filter f : filters) set.add(f);
        return new AutoDeleteRule(1, 2, set);
    }

    @Test
    void inviteLinksAreCaughtInTheirCommonForms() {
        var r = rule(Filter.INVITES);

        assertEquals(Filter.INVITES, r.violation("join https://discord.gg/abc123", 0));
        assertEquals(Filter.INVITES, r.violation("discord.gg/abc123", 0));
        assertEquals(Filter.INVITES, r.violation("https://discord.com/invite/abc123", 0));
        assertEquals(Filter.INVITES, r.violation("https://DISCORDAPP.com/invite/xyz", 0));
    }

    @Test
    void anInviteFilterLeavesOrdinaryLinksAlone() {
        assertNull(rule(Filter.INVITES).violation("see https://runescape.wiki/w/Telos", 0));
        assertNull(rule(Filter.INVITES).violation("my discord is great", 0));
    }

    @Test
    void theLinkFilterCatchesLinksAndInvitesButNotPlainText() {
        var r = rule(Filter.LINKS);

        assertEquals(Filter.LINKS, r.violation("look https://example.com/a?b=1", 0));
        assertEquals(Filter.LINKS, r.violation("www.example.com", 0));
        assertEquals(Filter.LINKS, r.violation("discord.gg/abc", 0));
        assertNull(r.violation("no links here, just example dot com", 0));
    }

    @Test
    void theAttachmentFilterCatchesFilesAndImages() {
        var r = rule(Filter.ATTACHMENTS);

        assertEquals(Filter.ATTACHMENTS, r.violation("", 1));
        assertNull(r.violation("just text", 0));
    }

    @Test
    void anInviteIsReportedAsAnInviteWhenBothFiltersAreOn() {
        assertEquals(Filter.INVITES, rule(Filter.INVITES, Filter.LINKS).violation("discord.gg/abc", 0));
    }

    @Test
    void aRuleWithNoFiltersDeletesNothing() {
        assertNull(rule().violation("https://discord.gg/abc", 3));
    }

    @Test
    void theNoticeNamesTheMemberAndWhatIsNotAllowed() {
        assertEquals("<@5>, invite links aren't allowed in this channel.", AutoDeleteRule.notice("<@5>", Filter.INVITES));
        assertEquals("<@5>, links aren't allowed in this channel.", AutoDeleteRule.notice("<@5>", Filter.LINKS));
        assertEquals("<@5>, files and images aren't allowed in this channel.", AutoDeleteRule.notice("<@5>", Filter.ATTACHMENTS));
    }

    private static AutoDeleteRule wordRule(java.util.List<String> words, AutoDeleteRule.RoleMode mode, java.util.Set<Long> roles) {
        return new AutoDeleteRule(1, 2, EnumSet.of(Filter.TEXT), words, mode, roles, 0);
    }

    @Test
    void certainWordsAreCaughtAnywhereInTheMessageIgnoringCase() {
        var r = wordRule(java.util.List.of("free nitro", "SCAM"), AutoDeleteRule.RoleMode.EVERYONE, java.util.Set.of());

        assertEquals(Filter.TEXT, r.violation("Get FREE Nitro here", 0));
        assertEquals(Filter.TEXT, r.violation("this is a scam!", 0));
        assertNull(r.violation("a perfectly fine message", 0));
    }

    @Test
    void wordsAreMatchedAsPlainTextNeverAsAPattern() {
        var r = wordRule(java.util.List.of("a.b+c"), AutoDeleteRule.RoleMode.EVERYONE, java.util.Set.of());

        assertEquals(Filter.TEXT, r.violation("see a.b+c now", 0));
        assertNull(r.violation("aXbbbc", 0));
    }

    @Test
    void aRuleWithoutWordsOrTheTextFilterNeverCatchesByText() {
        assertNull(wordRule(java.util.List.of(), AutoDeleteRule.RoleMode.EVERYONE, java.util.Set.of()).violation("anything", 0));
        assertNull(rule(Filter.INVITES).violation("free nitro", 0));
    }

    @Test
    void theRoleConditionDecidesWhoTheRuleIsFor() {
        var only = wordRule(java.util.List.of("abc"), AutoDeleteRule.RoleMode.ONLY_WITH, java.util.Set.of(7L));
        var except = wordRule(java.util.List.of("abc"), AutoDeleteRule.RoleMode.EXCEPT_WITH, java.util.Set.of(7L));
        var all = wordRule(java.util.List.of("abc"), AutoDeleteRule.RoleMode.EVERYONE, java.util.Set.of());

        org.junit.jupiter.api.Assertions.assertTrue(only.appliesTo(java.util.Set.of(7L, 8L)));
        org.junit.jupiter.api.Assertions.assertFalse(only.appliesTo(java.util.Set.of(8L)));
        org.junit.jupiter.api.Assertions.assertFalse(except.appliesTo(java.util.Set.of(7L)));
        org.junit.jupiter.api.Assertions.assertTrue(except.appliesTo(java.util.Set.of(8L)));
        org.junit.jupiter.api.Assertions.assertTrue(all.appliesTo(java.util.Set.of()));
    }

    @Test
    void wordsAreValidatedAndCleaned() {
        org.junit.jupiter.api.Assertions.assertNotNull(AutoDeleteRule.wordProblem("ab"));
        org.junit.jupiter.api.Assertions.assertNotNull(AutoDeleteRule.wordProblem("x".repeat(51)));
        org.junit.jupiter.api.Assertions.assertNull(AutoDeleteRule.wordProblem(" scam "));
        assertEquals(java.util.List.of("Scam", "free nitro"), AutoDeleteRule.cleanWords(java.util.Arrays.asList(" Scam ", "", "SCAM", "free nitro", null)));
    }

    @Test
    void theOriginalThreeFilterRuleIsForEveryoneAndDeletesAtOnce() {
        var r = rule(Filter.LINKS);

        assertEquals(AutoDeleteRule.RoleMode.EVERYONE, r.roleMode());
        assertEquals(0, r.delaySeconds());
        org.junit.jupiter.api.Assertions.assertTrue(r.appliesTo(java.util.Set.of(1L)));
    }
}
