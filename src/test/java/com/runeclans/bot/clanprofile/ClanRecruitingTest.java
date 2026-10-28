package com.runeclans.bot.clanprofile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanRecruitingTest {

    @Test
    void onlyDiscordInvitesAreAccepted() {
        assertTrue(ClanRecruiting.validInvite("https://discord.gg/abc123"));
        assertTrue(ClanRecruiting.validInvite("https://discord.com/invite/Younglings-RS"));
        assertTrue(ClanRecruiting.validInvite("https://www.discord.com/invite/abc/"));
        assertFalse(ClanRecruiting.validInvite("http://discord.gg/abc123"), "https only");
        assertFalse(ClanRecruiting.validInvite("https://discord.gg.evil.test/abc"));
        assertFalse(ClanRecruiting.validInvite("https://evil.test/discord.gg/abc"));
        assertFalse(ClanRecruiting.validInvite("https://discord.gg/abc?x=<script>"));
        assertFalse(ClanRecruiting.validInvite(null));
    }

    @Test
    void aNewServerHasAnEmptyPost() {
        ClanRecruiting empty = ClanRecruiting.empty(1L);
        assertTrue(empty.post().isEmpty());
        assertFalse(empty.applyFormId() != null);
    }
}
