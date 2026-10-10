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
                            boolean linkButton, String linkButtonLabel) {

    /** What gets sent: the text line, the embed, or both (the combination Dyno calls "Embed and Text"). */
    public enum MessageType {
        MESSAGE, EMBED, EMBED_TEXT;

        boolean hasText() {
            return this != EMBED;
        }

        boolean hasEmbed() {
            return this != MESSAGE;
        }
    }

    public record EmbedField(String name, String value, boolean inline) {}

    /** Off, with a sensible starting message, until an admin turns it on. */
    public static WelcomeConfig defaults(long guildId) {
        return new WelcomeConfig(guildId, false, MessageType.EMBED_TEXT, null, false,
                "Welcome to **{server}**, {user} 👋", null, "Welcome to {server}", "",
                "We're glad to have you here!", "", "", "", "", "", "", List.of(), false, "Link your RuneScape name");
    }
}
