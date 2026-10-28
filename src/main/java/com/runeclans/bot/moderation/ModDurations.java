package com.runeclans.bot.moderation;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads "30m", "2h", "7d" and "1d12h" into minutes, and words them back. */
public final class ModDurations {
    private ModDurations() {}

    /** Discord caps a timeout at 28 days. */
    public static final int MAX_TIMEOUT_MINUTES = 28 * 24 * 60;

    private static final Pattern PART = Pattern.compile("(\\d{1,6})\\s*([dhm])");

    /** Minutes, or empty if the text isn't a duration (anything left over after the parts, or nothing at all, is refused). */
    public static OptionalInt parseMinutes(String text) {
        if (text == null) return OptionalInt.empty();
        String s = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return OptionalInt.empty();
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return OptionalInt.empty();
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> n * 24 * 60;
                case "h" -> n * 60;
                default -> n;
            };
            end = m.end();
            if (total > Integer.MAX_VALUE) return OptionalInt.empty();
        }
        if (end != s.length() || total <= 0) return OptionalInt.empty();
        return OptionalInt.of((int) total);
    }

    /** "1 day", "2 hours", "1 day 12 hours", "45 minutes". */
    public static String words(int minutes) {
        int days = minutes / (24 * 60);
        int hours = minutes % (24 * 60) / 60;
        int mins = minutes % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append(days == 1 ? " day" : " days");
        if (hours > 0) out.append(out.length() > 0 ? " " : "").append(hours).append(hours == 1 ? " hour" : " hours");
        if (mins > 0) out.append(out.length() > 0 ? " " : "").append(mins).append(mins == 1 ? " minute" : " minutes");
        return out.toString();
    }
}
