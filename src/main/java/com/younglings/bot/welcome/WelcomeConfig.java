package com.younglings.bot.welcome;

import java.util.List;

/**
 * One server's welcome message, as set on the website. Mirrors what Dyno's Welcome module offers: a plain message,
 * an embed, or both, with every embed part optional. Every text part may use the variables listed in
 * {@link WelcomeTemplate}.
 * <p>
 * The welcome channel always receives the message. With {@code alsoDm} the new member is also sent a copy by DM,
 * when their DMs are open; if they aren't, only the channel post happens.
 */
public record WelcomeConfig(long guildId, boolean enabled, MessageType messageType, Long channelId, boolean alsoDm,
                            String content, Integer color, String title, String titleUrl, String description,
                            String authorName, String authorIconUrl, String thumbnailUrl, String imageUrl,
                            String footerText, String footerIconUrl, List<EmbedField> fields,
                            boolean linkButton, String linkButtonLabel, String linkButtonStyle) {

    /** The link button as it was before it could be coloured: blue. */
    public WelcomeConfig(long guildId, boolean enabled, MessageType messageType, Long channelId, boolean alsoDm,
                         String content, Integer color, String title, String titleUrl, String description,
                         String authorName, String authorIconUrl, String thumbnailUrl, String imageUrl,
                         String footerText, String footerIconUrl, List<EmbedField> fields,
                         boolean linkButton, String linkButtonLabel) {
        this(guildId, enabled, messageType, channelId, alsoDm, content, color, title, titleUrl, description, authorName, authorIconUrl,
                thumbnailUrl, imageUrl, footerText, footerIconUrl, fields, linkButton, linkButtonLabel, "primary");
    }

    /** The colours a button can have. */
    public static final List<String> BUTTON_STYLES = List.of("primary", "secondary", "success", "danger");

    /**
     * What gets sent: the text line, the embed, or both (the combination Dyno calls "Embed and Text"), or a container.
     * <p>
     * A container is a newer kind of Discord message (Components V2) that looks like an embed but is built from blocks, which is what
     * lets a button sit inside it: title, text, fields and footer are all ordinary text with markdown (so links work everywhere, and
     * the footer is small grey text), and the link button can go anywhere with the {@code {rs_button}} variable. Discord does not allow a
     * container in the same message as a text line or an embed, so a container has neither.
     */
    public enum MessageType {
        MESSAGE, EMBED, EMBED_TEXT, CONTAINER;

        boolean hasText() {
            return this == MESSAGE || this == EMBED_TEXT;
        }

        /** Whether the embed's parts (title, description, fields, ...) are used, as an embed or as a container's blocks. */
        boolean hasEmbed() {
            return this != MESSAGE;
        }

        boolean isContainer() {
            return this == CONTAINER;
        }
    }

    public record EmbedField(String name, String value, boolean inline) {}

    /** Off, with a sensible starting message, until an admin turns it on. */
    public static WelcomeConfig defaults(long guildId) {
        return new WelcomeConfig(guildId, false, MessageType.EMBED_TEXT, null, false,
                "Welcome to **{server}**, {user} 👋", null, "Welcome to {server}", "",
                "We're glad to have you here!", "", "", "", "", "", "", List.of(), false, "Link your RuneScape name", "primary");
    }
}
