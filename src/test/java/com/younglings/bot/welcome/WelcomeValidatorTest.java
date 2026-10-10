package com.younglings.bot.welcome;

import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a welcome message is refused for before it is saved. */
class WelcomeValidatorTest {
    private static WelcomeConfig base() {
        return WelcomeConfig.defaults(1L);
    }

    private static WelcomeConfig with(java.util.function.UnaryOperator<Builder> change) {
        return change.apply(new Builder(base())).build();
    }

    /** Just enough of a builder for these tests. */
    private static final class Builder {
        WelcomeConfig c;
        Builder(WelcomeConfig c) { this.c = c; }
        Builder enabled(boolean v) { c = new WelcomeConfig(c.guildId(), v, c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder channel(Long v) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), v, c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder type(MessageType v) { c = new WelcomeConfig(c.guildId(), c.enabled(), v, c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder content(String v) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), v, c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder embed(String title, String titleUrl, String description) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), title, titleUrl, description, c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder author(String name, String icon) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), name, icon, c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder images(String thumbnail, String image) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), thumbnail, image, c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder footer(String text, String icon) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), text, icon, c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder color(Integer v) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), v, c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), c.linkButton(), c.linkButtonLabel()); return this; }
        Builder fields(List<EmbedField> v) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), v, c.linkButton(), c.linkButtonLabel()); return this; }
        Builder button(boolean on, String label) { c = new WelcomeConfig(c.guildId(), c.enabled(), c.messageType(), c.channelId(), c.alsoDm(), c.content(), c.color(), c.title(), c.titleUrl(), c.description(), c.authorName(), c.authorIconUrl(), c.thumbnailUrl(), c.imageUrl(), c.footerText(), c.footerIconUrl(), c.fields(), on, label); return this; }
        WelcomeConfig build() { return c; }
    }

    private static boolean mentions(List<String> problems, String fragment) {
        return problems.stream().anyMatch(p -> p.contains(fragment));
    }

    @Test
    void theStartingMessageIsFineAndSwitchedOff() {
        assertEquals(List.of(), WelcomeValidator.validate(base()));
    }

    @Test
    void turningItOnNeedsAChannel() {
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.enabled(true))), "welcome channel"));
        assertEquals(List.of(), WelcomeValidator.validate(with(b -> b.enabled(true).channel(123L))));
    }

    @Test
    void aPlainMessageNeedsSomeText() {
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.type(MessageType.MESSAGE).content("  "))), "empty"));
    }

    @Test
    void anEmbedNeedsSomethingInIt() {
        var empty = with(b -> b.type(MessageType.EMBED).content("").embed("", "", ""));
        assertTrue(mentions(WelcomeValidator.validate(empty), "embed is empty"));
        assertEquals(List.of(), WelcomeValidator.validate(with(b -> b.type(MessageType.EMBED).embed("", "", "Hello"))));
    }

    @Test
    void textLimitsAreEnforcedWithTheLengthInTheMessage() {
        var problems = WelcomeValidator.validate(with(b -> b.content("x".repeat(1900)).embed("t".repeat(300), "", "d".repeat(4000))));
        assertTrue(mentions(problems, "message text can be at most 1800"));
        assertTrue(mentions(problems, "title can be at most 256"));
        assertTrue(mentions(problems, "description can be at most 3900"));
    }

    @Test
    void iconsAndLinksNeedTheirTextAndAValidWebAddress() {
        var problems = WelcomeValidator.validate(with(b -> b.embed("", "https://x.example", "d").author("", "https://i.example/a.png").footer("", "https://i.example/f.png")));
        assertTrue(mentions(problems, "title link needs a title"));
        assertTrue(mentions(problems, "author icon needs an author name"));
        assertTrue(mentions(problems, "footer icon needs footer text"));

        var bad = WelcomeValidator.validate(with(b -> b.images("not a url", "ftp://x.example/i.png")));
        assertTrue(mentions(bad, "thumbnail must be a full web address"));
        assertTrue(mentions(bad, "image must be a full web address"));
    }

    @Test
    void theAvatarVariableIsAllowedWhereAPictureGoes() {
        assertEquals(List.of(), WelcomeValidator.validate(with(b -> b.images("{avatar}", "{avatar}"))));
    }

    @Test
    void fieldsNeedANameAndValueAndStayWithinTheLimits() {
        var many = new java.util.ArrayList<EmbedField>();
        for (int i = 0; i < 26; i++) many.add(new EmbedField("n" + i, "v", false));
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.fields(many))), "at most 25 fields"));

        var problems = WelcomeValidator.validate(with(b -> b.fields(List.of(new EmbedField("", "v", false), new EmbedField("n", "v".repeat(1100), true)))));
        assertTrue(mentions(problems, "Field 1 needs both a name and a value"));
        assertTrue(mentions(problems, "value of field 2 can be at most 1024"));
    }

    @Test
    void theWholeEmbedHasAnOverallLimit() {
        var fields = new java.util.ArrayList<EmbedField>();
        for (int i = 0; i < 6; i++) fields.add(new EmbedField("n", "v".repeat(1000), false));
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.fields(fields))), "too long overall"));
    }

    @Test
    void aColourMustBeAValidRgbValue() {
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.color(0x1000000))), "colour"));
        assertEquals(List.of(), WelcomeValidator.validate(with(b -> b.color(0xAA2222))));
    }

    @Test
    void theLinkButtonNeedsAShortLabel() {
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.button(true, " "))), "needs a label"));
        assertTrue(mentions(WelcomeValidator.validate(with(b -> b.button(true, "x".repeat(81)))), "at most 80"));
        assertEquals(List.of(), WelcomeValidator.validate(with(b -> b.button(false, ""))));
    }
}
