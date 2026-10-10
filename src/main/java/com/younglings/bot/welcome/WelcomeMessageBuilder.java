package com.younglings.bot.welcome;

import com.younglings.bot.announcement.PostMarkup;
import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.EnumSet;

/** Turns a {@link WelcomeConfig} into the message Discord is sent, with the variables filled in for one member. */
public final class WelcomeMessageBuilder {
    /** The id of the "link your RuneScape name" button: an Embedded Post's {@code rs} action, so {@code PostActionListener} already answers it. */
    static final String LINK_BUTTON_ID = PostMarkup.BUTTON_ID_PREFIX + "welcome:rs";

    private WelcomeMessageBuilder() {}

    /**
     * @param withLinkButton whether to attach the link button — never in a DM, where the button has no server to work in
     * @throws IllegalStateException if the config has nothing at all to send
     */
    public static MessageCreateData build(WelcomeConfig c, WelcomeTemplate.Lookup lookup, long userId, boolean withLinkButton) {
        MessageCreateBuilder message = new MessageCreateBuilder();

        if (c.messageType().hasText()) {
            String text = WelcomeTemplate.render(c.content(), lookup).strip();
            if (!text.isEmpty()) message.setContent(clip(text, 2000));
        }
        if (c.messageType().hasEmbed() && WelcomeValidator.hasEmbedContent(c)) {
            message.setEmbeds(embed(c, lookup).build());
        }
        // Only the new member is ever pinged, whatever the text says: a role or @everyone in a greeting would fire on every join.
        message.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).mentionUsers(userId);

        if (withLinkButton && c.linkButton()) {
            String label = c.linkButtonLabel().isBlank() ? "Link your RuneScape name" : c.linkButtonLabel();
            message.addComponents(ActionRow.of(Button.primary(LINK_BUTTON_ID, clip(label, 80))));
        }
        return message.build();
    }

    private static EmbedBuilder embed(WelcomeConfig c, WelcomeTemplate.Lookup lookup) {
        EmbedBuilder embed = new EmbedBuilder();
        if (c.color() != null) embed.setColor(c.color());

        String title = plain(c.title(), lookup);
        if (!title.isEmpty()) embed.setTitle(clip(title, 256), safeUrl(plain(c.titleUrl(), lookup)));

        String description = WelcomeTemplate.render(c.description(), lookup).strip();
        if (!description.isEmpty()) embed.setDescription(clip(description, 4096));

        String author = plain(c.authorName(), lookup);
        if (!author.isEmpty()) embed.setAuthor(clip(author, 256), null, safeUrl(plain(c.authorIconUrl(), lookup)));

        String thumbnail = safeUrl(plain(c.thumbnailUrl(), lookup));
        if (thumbnail != null) embed.setThumbnail(thumbnail);
        String image = safeUrl(plain(c.imageUrl(), lookup));
        if (image != null) embed.setImage(image);

        String footer = plain(c.footerText(), lookup);
        if (!footer.isEmpty()) embed.setFooter(clip(footer, 2048), safeUrl(plain(c.footerIconUrl(), lookup)));

        for (EmbedField field : c.fields()) {
            String name = plain(field.name(), lookup);
            String value = WelcomeTemplate.render(field.value(), lookup).strip();
            if (!name.isEmpty() && !value.isEmpty()) embed.addField(clip(name, 256), clip(value, 1024), field.inline());
        }
        return embed;
    }

    private static String plain(String template, WelcomeTemplate.Lookup lookup) {
        return WelcomeTemplate.renderPlain(template, lookup).strip();
    }

    /** The address if it is a usable web address once the variables are in, else {@code null}, so one bad address drops that picture instead of the whole welcome. */
    private static String safeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") ? url : null;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
