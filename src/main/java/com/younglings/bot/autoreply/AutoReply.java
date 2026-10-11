package com.younglings.bot.autoreply;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One automatic reply: when a message matches the trigger the bot answers with the reply text. {@code WORD} matches the trigger as a
 * whole word or phrase anywhere in the message ("hello" matches "oh hello there" but not "helloooo" or "shell"), {@code STARTS} only
 * at the very start, {@code EXACT} only when it is the entire message. Matching ignores capital letters. {@code cooldownSeconds}
 * stops it answering again straight away, so one busy channel can't turn a reply into spam.
 */
public record AutoReply(Long id, long guildId, String trigger, Match match, String reply, int cooldownSeconds, boolean enabled) {
    public static final int MAX_PER_GUILD = 25;
    public static final int MAX_TRIGGER = 100;
    public static final int MAX_REPLY = 1500;
    public static final int MIN_COOLDOWN = 5;
    public static final int MAX_COOLDOWN = 86_400;

    public enum Match { WORD, STARTS, EXACT }

    /** Whether {@code content} (a message's text) triggers this reply. */
    public boolean matches(String content) {
        if (content == null || content.isBlank() || trigger.isBlank()) return false;
        String text = content.strip().toLowerCase(Locale.ROOT);
        String wanted = trigger.strip().toLowerCase(Locale.ROOT);
        return switch (match) {
            case EXACT -> text.equals(wanted);
            case STARTS -> text.startsWith(wanted);
            case WORD -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(wanted) + "(?![\\p{L}\\p{N}])").matcher(text).find();
        };
    }
}
