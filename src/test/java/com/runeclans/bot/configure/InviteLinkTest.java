package com.runeclans.bot.configure;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InviteLinkTest {
    @Test
    void theUsualInviteFormsBecomeTheShortLink() {
        assertEquals(Optional.of("https://discord.gg/abc123"), InviteLink.normalize("https://discord.gg/abc123"));
        assertEquals(Optional.of("https://discord.gg/abc123"), InviteLink.normalize("discord.gg/abc123"));
        assertEquals(Optional.of("https://discord.gg/abc123"), InviteLink.normalize(" https://discord.com/invite/abc123/ "));
        assertEquals(Optional.of("https://discord.gg/ab-C9"), InviteLink.normalize("http://www.discordapp.com/invite/ab-C9"));
    }

    @Test
    void anythingThatIsNotADiscordInviteIsRefused() {
        assertEquals(Optional.empty(), InviteLink.normalize("https://example.com/invite/abc123"));
        assertEquals(Optional.empty(), InviteLink.normalize("https://discord.gg.evil.com/abc123"));
        assertEquals(Optional.empty(), InviteLink.normalize("https://discord.gg/abc123?x=1"));
        assertEquals(Optional.empty(), InviteLink.normalize("https://discord.gg/a"));
        assertEquals(Optional.empty(), InviteLink.normalize("javascript:alert(1)"));
        assertEquals(Optional.empty(), InviteLink.normalize(""));
        assertEquals(Optional.empty(), InviteLink.normalize(null));
    }
}
