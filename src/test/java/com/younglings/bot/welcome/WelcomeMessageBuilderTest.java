package com.younglings.bot.welcome;

import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message a welcome turns into for one member: its text, its embed, its button, and who it may ping. */
class WelcomeMessageBuilderTest {
    private static final WelcomeTemplate.Lookup LOOKUP = new WelcomeTemplate.Lookup() {
        public String userMention() { return "<@42>"; }
        public String username() { return "PvmRyan"; }
        public String avatarUrl() { return "https://cdn.example/avatar.png"; }
        public String serverName() { return "The Younglings"; }
        public String channelName() { return "welcome"; }
        public int memberCount() { return 128; }
        public String findUser(String name) { return null; }
        public String findRole(String name) { return null; }
        public String findChannel(String name) { return null; }
    };

    /** The exact message from the screenshot: text line, then an embed with author, title, description, image and footer. */
    private static WelcomeConfig screenshot(MessageType type, boolean button) {
        return new WelcomeConfig(1L, true, type, 10L, false,
                "Welcome to **{server}**, {user} 👋\n\nPlease follow the steps below to get started!", 0xAA2222,
                "Welcome To {server}", "", "We're glad to have you here!\n\nHead to <#800> to verify, {username}.",
                "{server}", "https://cdn.example/logo.png", "", "https://cdn.example/banner.png",
                "{server} • RuneScape Clan", "", List.of(new EmbedField("Members", "{count}", true)), button, "Link your RuneScape name");
    }

    @Test
    void embedAndTextSendsBothWithTheVariablesFilledIn() {
        MessageCreateData data = WelcomeMessageBuilder.build(screenshot(MessageType.EMBED_TEXT, false), LOOKUP, 42L, true);

        assertEquals("Welcome to **The Younglings**, <@42> 👋\n\nPlease follow the steps below to get started!", data.getContent());
        assertEquals(1, data.getEmbeds().size());
        MessageEmbed embed = data.getEmbeds().getFirst();
        assertEquals("Welcome To The Younglings", embed.getTitle());
        assertEquals("We're glad to have you here!\n\nHead to <#800> to verify, PvmRyan.", embed.getDescription());
        assertEquals("The Younglings", embed.getAuthor().getName());
        assertEquals("https://cdn.example/logo.png", embed.getAuthor().getIconUrl());
        assertEquals("https://cdn.example/banner.png", embed.getImage().getUrl());
        assertEquals("The Younglings • RuneScape Clan", embed.getFooter().getText());
        assertEquals(0xAA2222, embed.getColorRaw() & 0xFFFFFF);
        assertEquals("128", embed.getFields().getFirst().getValue());
        assertTrue(embed.getFields().getFirst().isInline());
    }

    @Test
    void messageOnlyHasNoEmbedAndEmbedOnlyHasNoText() {
        MessageCreateData message = WelcomeMessageBuilder.build(screenshot(MessageType.MESSAGE, false), LOOKUP, 42L, true);
        assertTrue(message.getEmbeds().isEmpty());
        assertTrue(message.getContent().startsWith("Welcome to **The Younglings**"));

        MessageCreateData embed = WelcomeMessageBuilder.build(screenshot(MessageType.EMBED, false), LOOKUP, 42L, true);
        assertEquals("", embed.getContent());
        assertEquals(1, embed.getEmbeds().size());
    }

    @Test
    void onlyTheNewMemberCanBePingedWhateverTheTextSays() {
        var config = new WelcomeConfig(1L, true, MessageType.MESSAGE, 10L, false, "{user} {everyone} {here} {&Admin} <@&5> <@99>", null,
                "", "", "", "", "", "", "", "", "", List.of(), false, "x");
        MessageCreateData data = WelcomeMessageBuilder.build(config, LOOKUP, 42L, true);

        assertTrue(data.getAllowedMentions().isEmpty(), "no role, @everyone or blanket user pings");
        assertEquals(List.of("42"), List.copyOf(data.getMentionedUsers()));
        assertTrue(data.getMentionedRoles().isEmpty());
    }

    @Test
    void theLinkButtonIsOnlyThereWhenAskedForAndNeverInADm() {
        var inChannel = WelcomeMessageBuilder.build(screenshot(MessageType.EMBED_TEXT, true), LOOKUP, 42L, true);
        assertEquals(1, inChannel.getComponents().size());
        assertTrue(inChannel.getComponents().toString().contains(WelcomeMessageBuilder.LINK_BUTTON_ID));

        assertTrue(WelcomeMessageBuilder.build(screenshot(MessageType.EMBED_TEXT, true), LOOKUP, 42L, false).getComponents().isEmpty(), "a DM has no server for the button to work in");
        assertTrue(WelcomeMessageBuilder.build(screenshot(MessageType.EMBED_TEXT, false), LOOKUP, 42L, true).getComponents().isEmpty());
    }

    @Test
    void theLinkButtonIsTheSameOneAnEmbeddedPostUsesSoTheExistingHandlerAnswersIt() {
        assertEquals("postbtn:welcome:rs", WelcomeMessageBuilder.LINK_BUTTON_ID);
        assertEquals("rs", WelcomeMessageBuilder.LINK_BUTTON_ID.split(":", 3)[2]);
    }

    @Test
    void theAvatarVariableWorksAsAThumbnail() {
        var config = new WelcomeConfig(1L, true, MessageType.EMBED, 10L, false, "", null, "Hi", "", "", "", "", "{avatar}", "", "", "", List.of(), false, "x");
        MessageEmbed embed = WelcomeMessageBuilder.build(config, LOOKUP, 42L, true).getEmbeds().getFirst();
        assertEquals("https://cdn.example/avatar.png", embed.getThumbnail().getUrl());
    }

    @Test
    void aBadPictureAddressDropsThatPictureNotTheWholeWelcome() {
        var config = new WelcomeConfig(1L, true, MessageType.EMBED, 10L, false, "", null, "Hi", "", "Body", "", "", "not a url", "also bad", "", "", List.of(), false, "x");
        MessageEmbed embed = WelcomeMessageBuilder.build(config, LOOKUP, 42L, true).getEmbeds().getFirst();
        assertEquals("Hi", embed.getTitle());
        assertNull(embed.getThumbnail());
        assertNull(embed.getImage());
    }

    @Test
    void aMentionInATitleBecomesTheNameSoItDoesNotShowAsAnId() {
        var config = new WelcomeConfig(1L, true, MessageType.EMBED, 10L, false, "", null, "Hello {user}", "", "Hello {user}", "", "", "", "", "", "", List.of(), false, "x");
        MessageEmbed embed = WelcomeMessageBuilder.build(config, LOOKUP, 42L, true).getEmbeds().getFirst();
        assertEquals("Hello PvmRyan", embed.getTitle());
        assertEquals("Hello <@42>", embed.getDescription());
    }

    @Test
    void aConfigWithNothingToSendIsRefused() {
        var empty = new WelcomeConfig(1L, true, MessageType.EMBED_TEXT, 10L, false, " ", null, "", "", "", "", "", "", "", "", "", List.of(), false, "x");
        assertThrows(IllegalStateException.class, () -> WelcomeMessageBuilder.build(empty, LOOKUP, 42L, true));
    }
}
