package com.runeclans.bot.ban;

import com.runeclans.bot.welcome.WelcomeConfig;
import com.runeclans.bot.welcome.WelcomeConfig.MessageType;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ban announcement message: its variables are filled for someone no longer in the server, and it can never ping anybody. */
class BanAnnouncementTest {
    private static MessageCreateData build(WelcomeConfig config) {
        return BanAnnouncementService.build(config, "PvmRyan", "https://cdn.example/a.png", "The Younglings", "bans", 127);
    }

    @Test
    void theDefaultIsOffAndHasNoChannel() {
        WelcomeConfig defaults = BanAnnouncementRepository.defaults(1L);
        assertFalse(defaults.enabled(), "ban announcements are off by default");
        assertEquals(null, defaults.channelId());
        assertFalse(defaults.alsoDm());
        assertFalse(defaults.linkButton());
    }

    @Test
    void theDefaultMessageNamesThePersonAndTheServerWithoutAMention() {
        MessageCreateData data = build(BanAnnouncementRepository.defaults(1L));
        assertEquals(1, data.getEmbeds().size());
        assertEquals("PvmRyan was banned", data.getEmbeds().getFirst().getTitle());
        assertEquals("**PvmRyan** has been banned from **The Younglings**.", data.getEmbeds().getFirst().getDescription());
    }

    @Test
    void nobodyIsPingedEvenIfTheTemplateTriesTo() {
        WelcomeConfig c = new WelcomeConfig(1L, true, MessageType.MESSAGE, 9L, false, "{user} {everyone} {here} {&Admin} <@1234> <@&55> @everyone", null,
                "", "", "", "", "", "", "", "", "", java.util.List.of(), true, "Link", "primary", "small");
        MessageCreateData data = build(c);
        assertTrue(data.getAllowedMentions().isEmpty(), "no mention type is allowed");
        assertTrue(data.getMentionedUsers().isEmpty());
        assertTrue(data.getMentionedRoles().isEmpty());
        assertFalse(data.getAllowedMentions().contains(Message.MentionType.EVERYONE));
        assertTrue(data.getComponents().isEmpty(), "a ban announcement never has the link button");
    }

    @Test
    void aContainerBanAnnouncementIsAlsoSilent() {
        WelcomeConfig c = new WelcomeConfig(1L, true, MessageType.CONTAINER, 9L, false, "", 0xED4245, "{username} was banned",
                "", "**{username}** is gone. {rs_button}", "", "", "", "", "", "", java.util.List.of(), false, "Link", "primary", "small");
        MessageCreateData data = build(c);
        assertTrue(data.getAllowedMentions().isEmpty());
        assertTrue(data.getMentionedUsers().isEmpty());
    }
}
