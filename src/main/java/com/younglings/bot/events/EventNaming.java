package com.younglings.bot.events;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the roles and channels of an event are called. Every name comes from a pattern an organiser can change ({@code {event} General} becomes the channel
 * {@code christmas-bingo-general}), filled in with the event's name and, for a team's or a player's own channel, theirs. A channel name is made the way
 * Discord likes them (lowercase, words joined by dashes, no odd characters); a role or category keeps its words as written. Pure, so it can be tested.
 */
public final class EventNaming {
    /** The longest a name of a role, channel or category can be on Discord. */
    public static final int MAX_LENGTH = 100;
    public static final String EVENT = "event";
    public static final String TEAM = "team";
    public static final String PLAYER = "player";

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z]*)}");

    private EventNaming() {}

    /** {@code pattern} with each {@code {variable}} replaced by its value in {@code values}; what is put in is never read again as a pattern. */
    public static String fill(String pattern, Map<String, String> values) {
        Matcher m = VARIABLE.matcher(pattern == null ? "" : pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1).toLowerCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** What is wrong with {@code pattern}, in words, or an empty list: only {@code allowed} variables, and something left once they are filled in. */
    public static List<String> problems(String pattern, Set<String> allowed, String what) {
        List<String> problems = new ArrayList<>();
        String text = pattern == null ? "" : pattern.strip();
        if (text.isEmpty()) {
            problems.add("Give the " + what + " a name pattern.");
            return problems;
        }
        if (text.length() > MAX_LENGTH) problems.add("Keep the " + what + " name pattern to " + MAX_LENGTH + " characters or fewer.");
        Matcher m = VARIABLE.matcher(text);
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (!allowed.contains(name)) {
                problems.add("The " + what + " name can't use {" + m.group(1) + "}" + (allowed.isEmpty() ? "." : "; use " + String.join(", ", allowed.stream().sorted().map(a -> "{" + a + "}").toList()) + "."));
            }
        }
        if (text.replace("{", "").replace("}", "").isBlank()) problems.add("Give the " + what + " name some words, not only braces.");
        return problems;
    }

    /** A text channel's name for {@code pattern}: made to Discord's liking, never empty ({@code fallback} stands in) and no longer than {@link #MAX_LENGTH}. */
    public static String channel(String pattern, Map<String, String> values, String fallback) {
        String slug = slug(fill(pattern, values));
        if (slug.isEmpty()) slug = slug(fallback);
        if (slug.isEmpty()) slug = "event";
        return trimDashes(cut(slug));
    }

    /** A role's or category's name for {@code pattern}: the words as written with tidy spacing, never empty and no longer than {@link #MAX_LENGTH}. */
    public static String label(String pattern, Map<String, String> values, String fallback) {
        String text = tidy(fill(pattern, values));
        if (text.isEmpty()) text = tidy(fallback);
        if (text.isEmpty()) text = "Event";
        return cut(text).strip();
    }

    /**
     * {@code text} the way Discord channel names go: letters and digits in lowercase with accents dropped, words joined by single dashes, apostrophes
     * dropped without breaking the word ("Drake's Bingo" is {@code drakes-bingo}). Empty when there is nothing usable in it.
     */
    public static String slug(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < plain.length(); ) {
            int c = plain.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\'' || c == '’' || c == 'ʼ') continue;
            if (Character.isLetterOrDigit(c) || c == '_') {
                if (dash && !out.isEmpty()) out.append('-');
                dash = false;
                out.appendCodePoint(c);
            } else {
                dash = true;
            }
        }
        return out.toString();
    }

    private static String tidy(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    }

    private static String cut(String text) {
        if (text.length() <= MAX_LENGTH) return text;
        int end = MAX_LENGTH;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static String trimDashes(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '-') end--;
        return text.substring(0, end);
    }

    /**
     * Makes every name in {@code names} different from the others by adding {@code -2}, {@code -3} and so on to the later ones (a space between for names
     * that are words, not channel names), keeping each within {@link #MAX_LENGTH}. Names that differ only in case are the same for a role or category too.
     */
    public static List<String> unique(List<String> names, String joiner) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String name : names) {
            String candidate = name;
            int n = 2;
            while (!seen.add(candidate.toLowerCase(Locale.ROOT))) {
                String suffix = joiner + n++;
                String base = name.length() + suffix.length() > MAX_LENGTH ? name.substring(0, MAX_LENGTH - suffix.length()) : name;
                candidate = trimDashes(base) + suffix;
            }
            result.add(candidate);
        }
        return result;
    }
}
