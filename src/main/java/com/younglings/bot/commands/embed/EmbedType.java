package com.younglings.bot.commands.embed;

/**
 * Registry of pre-designed embeds {@code /embed} can post — add a case here (and a branch in
 * {@link EmbedService#postEmbed}) for each new one; the post modal's dropdown is built from
 * {@link #values()} automatically.
 */
public enum EmbedType {
    TEAMFORMING("Teamforming Panel");

    private final String displayName;

    EmbedType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
