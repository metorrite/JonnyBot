package com.younglings.bot.highlight;

import java.util.Locale;
import java.util.regex.Pattern;

/** Whether a message contains a member's word: case-insensitive, either as a whole word or anywhere inside other text. */
public final class HighlightMatcher {
    private HighlightMatcher() {}

    public static boolean matches(String content, String word, boolean wholeWord) {
        if (content == null || word == null || word.isEmpty()) return false;
        String text = content.toLowerCase(Locale.ROOT);
        String needle = word.toLowerCase(Locale.ROOT);
        if (!text.contains(needle)) return false; // cheap check first: most messages contain nobody's word
        if (!wholeWord) return true;
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}_])", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /** The word as stored: surrounding space and doubled spaces removed. */
    public static String clean(String raw) {
        return raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
    }
}
