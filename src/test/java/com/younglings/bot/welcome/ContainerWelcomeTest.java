package com.younglings.bot.welcome;

import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponentUnion;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The container form of a welcome: embed-like parts as blocks, with the link button placed where {rs_button} says. */
class ContainerWelcomeTest {
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

    private static WelcomeConfig container(String title, String description, String footer, List<EmbedField> fields, boolean button, String style, String thumbnail, String image) {
        return new WelcomeConfig(1L, true, MessageType.CONTAINER, 10L, false, "ignored text line", 0xAA2222, title, "", description, "", "",
                thumbnail, image, footer, "", fields, button, "Link your RuneScape name", style);
    }

    private static List<ContainerChildComponentUnion> children(MessageCreateData data) {
        assertEquals(1, data.getComponents().size(), "a container is the whole message");
        Container container = data.getComponents().getFirst().asContainer();
        return container.getComponents();
    }

    private static String describe(ContainerChildComponentUnion c) {
        return switch (c.getType()) {
            case TEXT_DISPLAY -> "text:" + c.asTextDisplay().getContent();
            case SECTION -> "section:" + c.asSection().getContentComponents().stream().map(t -> t.asTextDisplay().getContent()).reduce("", String::concat)
                    + "|" + c.asSection().getAccessory().getType();
            case ACTION_ROW -> "row";
            case MEDIA_GALLERY -> "gallery";
            case SEPARATOR -> "separator";
            default -> c.getType().name();
        };
    }

    private static Button theButton(MessageCreateData data) {
        for (ContainerChildComponentUnion child : children(data)) {
            if (child.getType() == Component.Type.SECTION && child.asSection().getAccessory() instanceof Button b) return b;
            if (child.getType() == Component.Type.ACTION_ROW) return child.asActionRow().getButtons().getFirst();
        }
        return null;
    }

    @Test
    void aContainerIsTheWholeMessageWithNoTextLineAndNoEmbed() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Welcome to {server}", "We're glad you're here, {username}!", "", List.of(), false, "primary", "", ""), LOOKUP, 42L, true);

        assertEquals("", data.getContent());
        assertTrue(data.getEmbeds().isEmpty());
        assertTrue(data.isUsingComponentsV2());
        assertEquals(List.of("text:## Welcome to The Younglings\nWe're glad you're here, PvmRyan!"), children(data).stream().map(ContainerWelcomeTest::describe).toList());
    }

    @Test
    void theFooterIsSmallGreyTextAndHyperlinksWorkAnywhere() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Hi", "Read the [rules](https://example.com/rules).", "[The Younglings](https://example.com) • RuneScape Clan",
                List.of(new EmbedField("[Website](https://example.com)", "https://example.com", true)), false, "primary", "", ""), LOOKUP, 42L, true);

        List<String> blocks = children(data).stream().map(ContainerWelcomeTest::describe).toList();
        assertTrue(blocks.contains("text:-# [The Younglings](https://example.com) • RuneScape Clan"), blocks.toString());
        assertTrue(blocks.contains("text:**[Website](https://example.com)**\nhttps://example.com"), blocks.toString());
    }

    @Test
    void theButtonGoesWhereRsButtonIsWrittenBesideThatLinesText() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Hi", "Intro", "The Younglings {rs_button}", List.of(), true, "secondary", "", ""), LOOKUP, 42L, true);

        List<String> blocks = children(data).stream().map(ContainerWelcomeTest::describe).toList();
        assertTrue(blocks.contains("section:-# The Younglings|BUTTON"), blocks.toString());
        assertFalse(blocks.contains("row"), "placed once, so not added again at the bottom");
        assertEquals(ButtonStyle.SECONDARY, theButton(data).getStyle());
        assertEquals(WelcomeMessageBuilder.LINK_BUTTON_ID, theButton(data).getCustomId());
    }

    @Test
    void aButtonOnALineOfItsOwnStillGetsAnyBlockOfText() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Hi", "Before\n{rs_button}\nAfter", "", List.of(), true, "primary", "", ""), LOOKUP, 42L, true);

        List<String> blocks = children(data).stream().map(ContainerWelcomeTest::describe).toList();
        assertEquals(List.of("text:## Hi\nBefore", "section:⠀|BUTTON", "text:After"), blocks);
    }

    @Test
    void withNoMarkerTheButtonIsAddedAtTheBottomInsideTheContainer() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Hi", "Body", "Footer", List.of(), true, "success", "", ""), LOOKUP, 42L, true);

        List<String> blocks = children(data).stream().map(ContainerWelcomeTest::describe).toList();
        assertEquals("row", blocks.getLast());
        assertEquals(ButtonStyle.SUCCESS, theButton(data).getStyle());
    }

    @Test
    void aDmGetsNoButtonAndTheMarkerIsDropped() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Hi", "Body", "Footer {rs_button}", List.of(), true, "primary", "", ""), LOOKUP, 42L, false);

        List<String> blocks = children(data).stream().map(ContainerWelcomeTest::describe).toList();
        assertTrue(blocks.contains("text:-# Footer"), blocks.toString());
        assertEquals(null, theButton(data));
    }

    @Test
    void theMarkerIsDroppedWhenTheButtonIsOffAndOnlyTheFirstOneGetsTheButton() {
        MessageCreateData off = WelcomeMessageBuilder.build(container("Hi", "Click {rs_button} here", "", List.of(), false, "primary", "", ""), LOOKUP, 42L, true);
        assertEquals(List.of("text:## Hi\nClick  here"), children(off).stream().map(ContainerWelcomeTest::describe).toList());

        MessageCreateData twice = WelcomeMessageBuilder.build(container("Hi", "One {rs_button}\nTwo {rs_button}", "", List.of(), true, "primary", "", ""), LOOKUP, 42L, true);
        long buttons = children(twice).stream().filter(c -> c.getType() == Component.Type.SECTION).count();
        assertEquals(1, buttons);
    }

    @Test
    void aThumbnailSitsBesideTheFirstBlockAndTheImageIsAGallery() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("Hi", "Body", "", List.of(), false, "primary", "{avatar}", "https://cdn.example/banner.png"), LOOKUP, 42L, true);

        List<String> blocks = children(data).stream().map(ContainerWelcomeTest::describe).toList();
        assertEquals(List.of("section:## Hi\nBody|THUMBNAIL", "gallery"), blocks);
    }

    @Test
    void fieldsAreStackedInOneBlockIgnoringInline() {
        MessageCreateData data = WelcomeMessageBuilder.build(container("", "", "", List.of(new EmbedField("A", "one", true), new EmbedField("B", "two", false)), false, "primary", "", ""), LOOKUP, 42L, true);

        assertEquals(List.of("text:**A**\none\n\n**B**\ntwo"), children(data).stream().map(ContainerWelcomeTest::describe).toList());
    }

    @Test
    void theAccentColourIsTheEmbedColour() {
        Container container = WelcomeMessageBuilder.build(container("Hi", "Body", "", List.of(), false, "primary", "", ""), LOOKUP, 42L, true).getComponents().getFirst().asContainer();

        assertEquals(0xAA2222, container.getAccentColorRaw() & 0xFFFFFF);
    }

    @Test
    void splitFindsTheFirstMarkerLine() {
        var pieces = ContainerWelcome.split("a\nb {rs_button} c\nd {rs_button}\ne");

        assertTrue(pieces.hasButton());
        assertEquals("a", pieces.before());
        assertEquals("b  c", pieces.line());
        assertEquals("d \ne", pieces.after());
        assertFalse(ContainerWelcome.split("nothing here").hasButton());
    }

    private static WelcomeConfig withFooterStyle(String style, String footer) {
        return new WelcomeConfig(1L, true, MessageType.CONTAINER, 10L, false, "", null, "Hi", "", "Body", "", "", "", "", footer, "", List.of(), false, "Link", "primary", style);
    }

    @Test
    void theFooterCanBeSmallGreyNormalOrBold() {
        assertEquals("text:-# The Younglings", describe(lastText(withFooterStyle("small", "The Younglings"))));
        assertEquals("text:The Younglings", describe(lastText(withFooterStyle("normal", "The Younglings"))));
        assertEquals("text:**The Younglings**", describe(lastText(withFooterStyle("bold", "The Younglings"))));
    }

    @Test
    void anUnknownFooterStyleFallsBackToSmall() {
        assertEquals("text:-# The Younglings", describe(lastText(withFooterStyle("loud", "The Younglings"))));
    }

    @Test
    void aBoldFooterKeepsTheButtonBesideIt() {
        MessageCreateData data = WelcomeMessageBuilder.build(new WelcomeConfig(1L, true, MessageType.CONTAINER, 10L, false, "", null, "Hi", "", "Body", "", "", "", "",
                "The Younglings {rs_button}", "", List.of(), true, "Link", "secondary", "bold"), LOOKUP, 42L, true);

        assertTrue(children(data).stream().map(ContainerWelcomeTest::describe).toList().contains("section:**The Younglings**|BUTTON"));
    }

    private static ContainerChildComponentUnion lastText(WelcomeConfig c) {
        return children(WelcomeMessageBuilder.build(c, LOOKUP, 42L, true)).getLast();
    }
}
