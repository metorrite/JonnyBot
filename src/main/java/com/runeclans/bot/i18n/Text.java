package com.runeclans.bot.i18n;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.Interaction;

import java.util.Map;

/**
 * The message catalogue for code that has no {@link Messages} of its own (T-098): {@code Text.of(event, "split.title")}. Moving every
 * string onto the catalogue this way needs no new constructor parameter in the hundreds of classes that send text, nor in their tests.
 * <p>
 * Backed by the bot's {@link Messages} once it exists (it installs itself), so the server's language applies. Before that, and in tests that
 * never make one, every lookup is English from {@code i18n/messages_en.properties}, which is exactly the text the code used to hold.
 * Keys are checked by {@code MessagesTest}: a key used here that English lacks fails the build.
 */
public final class Text {
    private static volatile Messages installed;
    private static volatile Map<String, String> english;

    private Text() {}

    /** Called by {@link Messages} when the bot makes it. */
    static void install(Messages messages) {
        installed = messages;
    }

    /** The message in the language of the server the interaction came from; English in a DM. */
    public static String of(Interaction event, String key, Object... args) {
        Guild guild = event == null ? null : event.getGuild();
        return guild == null ? en(key, args) : of(guild.getIdLong(), key, args);
    }

    /** The message in the server's language. */
    public static String of(Guild guild, String key, Object... args) {
        return guild == null ? en(key, args) : of(guild.getIdLong(), key, args);
    }

    /** The message in the language of the server with this id. */
    public static String of(long guildId, String key, Object... args) {
        Messages messages = installed;
        return messages == null ? en(key, args) : messages.text(guildId, key, args);
    }

    /** The message in English: for text with no server to take a language from (DMs, logs shown to the bot's owners). */
    public static String en(String key, Object... args) {
        Messages messages = installed;
        if (messages != null) return messages.text(Messages.DEFAULT_LANGUAGE, key, args);
        Map<String, String> catalogue = english;
        if (catalogue == null) english = catalogue = Messages.loadEnglish();
        String template = catalogue.get(key);
        return template == null ? key : Messages.fill(template, args);
    }
}
