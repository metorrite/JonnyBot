package com.younglings.bot.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The rules a new poll must meet — the same limits the /poll panel in Discord enforces — shared by the admin and member routes. */
final class PollRules {
    static final int MAX_TITLE = 150;
    static final int MAX_OPTION = 100;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 6;
    static final int MAX_DURATION_HOURS = 24 * 30;

    private PollRules() {}

    /** @return what's wrong, or {@code null} if the poll is fine */
    static String validate(String title, List<String> options) {
        if (title.isBlank()) return "A poll needs a question.";
        if (title.length() > MAX_TITLE) return "Keep the question under " + MAX_TITLE + " characters.";
        if (options.size() < MIN_OPTIONS) return "A poll needs at least " + MIN_OPTIONS + " options.";
        if (options.size() > MAX_OPTIONS) return "A poll can have at most " + MAX_OPTIONS + " options.";

        Set<String> seen = new HashSet<>();
        for (String option : options) {
            if (option.length() > MAX_OPTION) return "An option is longer than " + MAX_OPTION + " characters.";
            if (!seen.add(option.toLowerCase(Locale.ROOT))) return "\"" + option + "\" is listed twice.";
        }
        return null;
    }

    /** @return what's wrong with a requested duration in hours, or {@code null} if it's fine (or absent) */
    static String validateDuration(Integer hours) {
        if (hours == null) return null;
        if (hours < 1 || hours > MAX_DURATION_HOURS) return "A poll can run for 1 hour up to " + (MAX_DURATION_HOURS / 24) + " days.";
        return null;
    }
}
