package com.runeclans.bot.clanprofile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The order a server's one join link is chosen in (T-181). */
class JoinLinksTest {
    @Test
    void serverSetupsInviteWins() {
        assertEquals("https://discord.gg/server", JoinLinks.choose("https://discord.gg/server", "https://discord.gg/recruits", "https://discord.gg/apply"));
    }

    @Test
    void theRecruitingInviteIsUsedWhenServerSetupHasNone() {
        assertEquals("https://discord.gg/recruits", JoinLinks.choose(null, "https://discord.gg/recruits", "https://discord.gg/apply"));
        assertEquals("https://discord.gg/recruits", JoinLinks.choose("  ", "https://discord.gg/recruits", null));
    }

    @Test
    void theApplyLinkOnlyCountsWhenItIsADiscordInvite() {
        assertEquals("https://discord.gg/apply", JoinLinks.choose(null, null, "https://discord.com/invite/apply"));
        assertNull(JoinLinks.choose(null, null, "https://example.org/apply-here"), "a web page is the Apply link, not a join link");
    }

    @Test
    void somethingThatIsNotAnInviteIsSkipped() {
        assertEquals("https://discord.gg/recruits", JoinLinks.choose("not a link", "discord.gg/recruits", null));
        assertNull(JoinLinks.choose(null, null, null));
    }
}
