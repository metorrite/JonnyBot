package com.younglings.bot.announcement;

import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostTextConverterTest {
    private static final String SAMPLE = """
            **CA Help Tickets — where we landed**

            **The idea:** a ticket members open to ask for help. Teamforming first.

            **What we agree on**
            • Tickets exist so requests don't get lost
            • 1–2 helpers per ticket, and helpers stay in their own lane

            **A ticket**
            1. A member presses the button
            2. A private channel opens
            • **CA Helper**: self-assign after accepting the guidelines
            """;

    @Test
    void theFirstBoldLineBecomesTheTitleAndLaterOnesBecomeSections() {
        String out = PostTextConverter.convert(SAMPLE);
        assertTrue(out.startsWith("## CA Help Tickets — where we landed\n"), out);
        assertTrue(out.contains("\n### What we agree on\n"), out);
        assertTrue(out.contains("\n### A ticket\n"), out);
    }

    @Test
    void bulletsBecomeListItemsAndNothingElseOnTheLineChanges() {
        String out = PostTextConverter.convert(SAMPLE);
        assertTrue(out.contains("\n- Tickets exist so requests don't get lost\n"), out);
        assertTrue(out.contains("\n- 1–2 helpers per ticket, and helpers stay in their own lane\n"), out);
        assertTrue(out.contains("\n- **CA Helper**: self-assign after accepting the guidelines"), "a bullet that starts with bold stays a bullet, not a heading");
    }

    @Test
    void aBoldLeadInFollowedByTextIsNotAHeading() {
        String out = PostTextConverter.convert(SAMPLE);
        assertTrue(out.contains("\n**The idea:** a ticket members open to ask for help. Teamforming first.\n"), out);
    }

    @Test
    void numberedListsTagsAndCodeBlocksAreLeftAlone() {
        String text = "**Title**\n1. one\n2. two\n~<LS>~\n~B-P_Go|rs~\n```\n**not a heading**\n• not a bullet\n```";
        String out = PostTextConverter.convert(text);
        assertTrue(out.contains("1. one\n2. two"));
        assertTrue(out.contains("~<LS>~\n~B-P_Go|rs~"));
        assertTrue(out.contains("```\n**not a heading**\n• not a bullet\n```"));
    }

    @Test
    void aTrailingColonOnAHeadingLineIsDropped() {
        assertEquals("## Roles\n### Next", PostTextConverter.convert("**Roles:**\n**Next**"));
    }

    @Test
    void ifTheTextDoesNotStartWithABoldLineEveryHeadingIsASection() {
        assertEquals("Some intro\n### Part one", PostTextConverter.convert("Some intro\n**Part one**"));
    }

    @Test
    void convertingTwiceChangesNothingMore() {
        String once = PostTextConverter.convert(SAMPLE);
        assertEquals(once, PostTextConverter.convert(once));
    }

    @Test
    void windowsLineEndingsAndTrailingSpacesAreTidied() {
        assertEquals("## T\n- a", PostTextConverter.convert("**T**  \r\n• a   \r\n"));
    }

    @Test
    void theConvertedPostParsesCleanlyAndFitsInAMessage() {
        PostMarkup.Parsed parsed = PostMarkup.parse(PostTextConverter.convert(SAMPLE), true);
        assertTrue(parsed.problems().isEmpty());
        new MessageCreateBuilder().useComponentsV2(true).setComponents(parsed.toContainer(Color.CYAN)).build();
        assertTrue(((TextDisplay) parsed.children().getFirst()).getContent().startsWith("## CA Help Tickets"));
    }
}
