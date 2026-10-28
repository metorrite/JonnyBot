package com.runeclans.bot.accountgate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Word rules for the names of people who join: a rule is a name pattern where {@code *} stands for any run of characters (so {@code free*nitro} matches
 * "FreeDiscordNitro", {@code *spam*} matches anything with "spam" in it). Matching is not case sensitive and always covers the whole name, so a rule
 * without a {@code *} only matches that exact name. To stop a careless rule from catching everyone, a rule needs at least {@link #MIN_LITERAL}
 * ordinary characters. Pure, so it can be tested without Discord.
 */
public final class NameRules {
    public static final int MIN_LITERAL = 3;
    public static final int MAX_RULES = 50;
    public static final int MAX_RULE_LENGTH = 64;

    private NameRules() {}

    /** What is wrong with {@code rule}, or null when it is fine. */
    public static String problem(String rule) {
        String text = rule == null ? "" : rule.strip();
        if (text.isEmpty()) return "A name rule can't be empty.";
        if (text.length() > MAX_RULE_LENGTH) return "\"" + shorten(text) + "\" is longer than " + MAX_RULE_LENGTH + " characters.";
        if (text.chars().anyMatch(Character::isISOControl) || text.contains("\n")) return "A name rule can't contain line breaks.";
        long literal = text.chars().filter(c -> c != '*').count();
        if (literal < MIN_LITERAL) return "\"" + shorten(text) + "\" needs at least " + MIN_LITERAL + " characters that are not *, or it would match too many people.";
        return null;
    }

    /** The rules cleaned up for storing: trimmed, lower case, without blanks or repeats, in order. */
    public static List<String> clean(List<String> rules) {
        List<String> out = new ArrayList<>();
        for (String rule : rules) {
            String text = rule == null ? "" : rule.strip().toLowerCase(Locale.ROOT);
            if (!text.isEmpty() && !out.contains(text)) out.add(text);
        }
        return out;
    }

    /** Whether {@code name} matches the single {@code rule}. */
    public static boolean matches(String rule, String name) {
        if (rule == null || name == null || problem(rule) != null) return false;
        StringBuilder regex = new StringBuilder();
        for (String part : rule.strip().toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (regex.length() > 0) regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        // a rule that starts or ends with * produces an empty quoted part at that end, which is harmless
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(name.strip().toLowerCase(Locale.ROOT)).matches();
    }

    /** The first rule that matches any of {@code names}, or null. */
    public static String firstMatch(List<String> rules, List<String> names) {
        for (String rule : rules) {
            for (String name : names) {
                if (name != null && matches(rule, name)) return rule;
            }
        }
        return null;
    }

    private static String shorten(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "…" : text;
    }
}
