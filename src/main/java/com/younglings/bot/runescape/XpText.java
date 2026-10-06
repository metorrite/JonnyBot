package com.younglings.bot.runescape;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Short, readable numbers for adventure-log text. RuneScape's XP milestones past level 99 come in whole millions
 * ("200000000XP in Necromancy"), so those read far better as "200M XP". Numbers that aren't a whole number of
 * millions are left exactly as they were.
 */
public final class XpText {
    private XpText() {}

    /** A run of seven or more digits (optionally comma-grouped), not part of a longer number, with an optional "XP" glued on. */
    private static final Pattern BIG_NUMBER = Pattern.compile("(?<![\\d,])(\\d{1,3}(?:,\\d{3}){2,}|\\d{7,})(XP)?");

    /** "200000000XP in Attack" or "I reached 200,000,000 XP" becomes "200M XP in Attack" / "I reached 200M XP". */
    public static String shorten(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = BIG_NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            long value = Long.parseLong(m.group(1).replace(",", ""));
            String replacement = value % 1_000_000 == 0 ? (value / 1_000_000) + "M" + (m.group(2) != null ? " XP" : "") : m.group(0);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** An XP amount for display: "200M" for whole millions, otherwise grouped with commas ("13,034,431"). */
    public static String amount(long xp) {
        return xp != 0 && xp % 1_000_000 == 0 ? (xp / 1_000_000) + "M" : String.format("%,d", xp);
    }
}
