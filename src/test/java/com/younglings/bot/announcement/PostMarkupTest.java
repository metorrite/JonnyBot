package com.younglings.bot.announcement;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostMarkupTest {
    private static PostMarkup.Parsed parse(String text) {
        return PostMarkup.parse(text, true);
    }

    private static List<Button> buttonsOf(PostMarkup.Parsed parsed) {
        return parsed.children().stream().filter(c -> c instanceof ActionRow)
                .flatMap(c -> ((ActionRow) c).getComponents().stream()).map(c -> (Button) c).toList();
    }

    /** Throws if Discord's message limits would reject the result. */
    private static void assertBuildsAsMessage(PostMarkup.Parsed parsed) {
        new MessageCreateBuilder().useComponentsV2(true).setComponents(parsed.toContainer(Color.CYAN)).build();
    }

    @Test
    void plainTextIsOneTextBlockExactlyAsBefore() {
        var parsed = parse("Hello **world**\n\nSecond paragraph");
        assertTrue(parsed.problems().isEmpty());
        assertEquals(1, parsed.children().size());
        assertEquals("Hello **world**\n\nSecond paragraph", ((TextDisplay) parsed.children().getFirst()).getContent());
    }

    @Test
    void theExampleFromTheRequestBuildsADividerAndThreeButtons() {
        var parsed = parse("A large body of text\n~<LS>~\n~B-P_PRIMARY~ ~B-S_SECONDARY~ ~B-D_DANGER~");
        assertEquals(3, parsed.children().size());
        assertInstanceOf(TextDisplay.class, parsed.children().get(0));
        assertInstanceOf(Separator.class, parsed.children().get(1));
        assertEquals(List.of(ButtonStyle.PRIMARY, ButtonStyle.SECONDARY, ButtonStyle.DANGER), buttonsOf(parsed).stream().map(Button::getStyle).toList());
        assertEquals(List.of("PRIMARY", "SECONDARY", "DANGER"), buttonsOf(parsed).stream().map(Button::getLabel).toList());
        assertBuildsAsMessage(parsed);
    }

    @Test
    void buttonsWithoutAnActionArePostedDisabledWithAWarningNotAnError() {
        var parsed = parse("~B-P_PRIMARY~");
        assertFalse(parsed.hasErrors());
        assertEquals(1, parsed.problems().size());
        assertTrue(buttonsOf(parsed).getFirst().isDisabled());
    }

    @Test
    void anActionButtonIsLiveAndCarriesAUniqueIdPerButton() {
        var parsed = parse("~B-P_Link|rs~ ~B-S_Again|rs~ ~B-G_Totals|citadel~");
        assertTrue(parsed.problems().isEmpty());
        assertEquals(List.of("postbtn:0:rs", "postbtn:1:rs", "postbtn:2:citadel"), buttonsOf(parsed).stream().map(Button::getCustomId).toList());
        assertTrue(buttonsOf(parsed).stream().noneMatch(Button::isDisabled));
    }

    @Test
    void aPreviewRendersEveryButtonDisabled() {
        var parsed = PostMarkup.parse("~B-P_Link|rs~", false);
        assertTrue(buttonsOf(parsed).getFirst().isDisabled());
    }

    @Test
    void linkButtonsNeedAnHttpUrl() {
        var ok = parse("~B-L_Site|https://example.com/a?b=1~");
        assertTrue(ok.problems().isEmpty());
        assertEquals("https://example.com/a?b=1", buttonsOf(ok).getFirst().getUrl());
        assertTrue(parse("~B-L_Site|example.com~").hasErrors());
        assertTrue(parse("~B-L_Site~").hasErrors());
    }

    @Test
    void anUnknownActionIsAnErrorThatNamesTheValidOnes() {
        var parsed = parse("~B-P_Go|teleport~");
        assertTrue(parsed.hasErrors());
        assertTrue(parsed.problemsText().contains("rs"));
        assertTrue(parsed.problemsText().contains("citadel"));
    }

    @Test
    void strikethroughAndOrdinaryTildesAreLeftAlone() {
        var parsed = parse("This is ~~crossed out~~ and so is ~this~ and ~<not closed");
        assertTrue(parsed.problems().isEmpty());
        assertEquals(1, parsed.children().size());
    }

    @Test
    void anUnknownLayoutTagIsReportedNotSilentlyPosted() {
        var parsed = parse("~<WHATEVER>~");
        assertTrue(parsed.hasErrors());
        assertTrue(parsed.children().isEmpty());
    }

    @Test
    void aTagBesideOtherTextIsAnError() {
        assertTrue(parse("Click here ~B-P_Go|rs~ now").hasErrors());
    }

    @Test
    void theDividerAndGapVariantsAllBuild() {
        var parsed = parse("~<LS>~\n~<LS-L>~\n~<LS-N>~\n~<LS-NL>~");
        assertEquals(4, parsed.children().stream().filter(c -> c instanceof Separator).count());
        assertEquals(List.of(true, true, false, false), parsed.children().stream().map(c -> ((Separator) c).isDivider()).toList());
        assertBuildsAsMessage(parsed);
    }

    @Test
    void colorSetsTheAccentAndRejectsBadHex() {
        var parsed = parse("hello\n~<COLOR-5865F2>~");
        assertEquals(new Color(0x5865F2), parsed.accent());
        assertEquals(new Color(0x5865F2), parsed.toContainer(Color.CYAN).getAccentColor());
        assertEquals(Color.CYAN, parse("hello").toContainer(Color.CYAN).getAccentColor());
        assertTrue(parse("~<COLOR-nope>~").hasErrors());
        assertNull(parse("~<COLOR-nope>~").accent());
    }

    @Test
    void anImageTagMakesAMediaGalleryAndNeedsAnHttpUrl() {
        var parsed = parse("~<IMG-https://example.com/a.png>~");
        assertTrue(parsed.problems().isEmpty());
        assertInstanceOf(MediaGallery.class, parsed.children().getFirst());
        assertBuildsAsMessage(parsed);
        assertTrue(parse("~<IMG-file.png>~").hasErrors());
    }

    @Test
    void sixButtonsOnOneLineIsAnError() {
        var parsed = parse("~B-P_1|rs~ ~B-P_2|rs~ ~B-P_3|rs~ ~B-P_4|rs~ ~B-P_5|rs~ ~B-P_6|rs~");
        assertTrue(parsed.hasErrors());
        assertTrue(parsed.problemsText().contains("at most 5"));
    }

    @Test
    void aBadStyleLetterOrEmptyLabelIsAnError() {
        assertTrue(parse("~B-X_Label|rs~").hasErrors());
        assertTrue(parse("~B-P_|rs~").hasErrors());
    }

    @Test
    void tooMuchTextIsAnErrorBeforeDiscordWouldRejectIt() {
        var parsed = parse("x".repeat(4001));
        assertTrue(parsed.hasErrors());
        assertTrue(parsed.problemsText().contains("4001"));
    }

    @Test
    void tooManyComponentsIsAnError() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) text.append("~<LS>~\n");
        assertTrue(parse(text.toString()).hasErrors());
    }

    @Test
    void aButtonRowKeepsItsPositionBetweenTextBlocks() {
        var parsed = parse("Intro\n~B-P_Go|rs~\nOutro");
        assertEquals(3, parsed.children().size());
        assertInstanceOf(TextDisplay.class, parsed.children().get(0));
        assertInstanceOf(ActionRow.class, parsed.children().get(1));
        assertInstanceOf(TextDisplay.class, parsed.children().get(2));
        assertBuildsAsMessage(parsed);
    }

    @Test
    void theLegendDocumentsEveryTagAndFitsInOneMessage() {
        for (String needle : List.of("~<LS>~", "~<LS-L>~", "~<LS-N>~", "~<LS-NL>~", "~<COLOR-", "~<IMG-", "~B-P_Label|action~", "rs", "citadel", "B-L_")) {
            assertTrue(PostMarkup.LEGEND.contains(needle), needle);
        }
        assertTrue(PostMarkup.LEGEND.length() < 4000);
    }

    @Test
    void theLegendsOwnExampleParsesCleanly() {
        var parsed = parse("Welcome! Link your RuneScape name to get started.\n~<LS>~\n~B-P_Link my RSN|rs~ ~B-S_Citadel this week|citadel~");
        assertTrue(parsed.problems().isEmpty());
        assertBuildsAsMessage(parsed);
    }
}
