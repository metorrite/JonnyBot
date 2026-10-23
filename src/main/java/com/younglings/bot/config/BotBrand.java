package com.younglings.bot.config;

/**
 * The bot's default public name (Drake, 2026-10-23: "RuneClan Bot"; it was JonnyBot). A rename is this one line, plus the bot's name and
 * avatar in the Discord Developer Portal. Text said inside one server says "the bot" instead, so it never contradicts the nickname and avatar
 * that server gave the bot on the dashboard's Bot appearance page. Catalogue messages write {@code {bot}} for this name (i18n Messages).
 * <p>
 * Code identifiers, packages, env var names and the User-Agent sent to third parties keep the JonnyBot name; this is user-facing text only.
 */
public final class BotBrand {
    /** The bot's name where no server's own name for it applies: product pages, cross-server messages, Discord audit-log reasons. */
    public static final String NAME = "RuneClan Bot";

    private BotBrand() {}
}
