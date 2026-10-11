package com.younglings.bot.autodelete;

import com.younglings.bot.autodelete.AutoDeleteRule.Filter;
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
}
