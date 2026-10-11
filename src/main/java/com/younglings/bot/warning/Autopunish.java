package com.younglings.bot.warning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Automatic consequences for piling up warnings: when a member's warning count reaches a rule's {@code threshold}, the rule's action is
 * applied once (at exactly that count, so a fifth warning does not repeat the action set for the third).
 */
public final class Autopunish {
    public static final int MAX_RULES = 5;
    public static final int MAX_THRESHOLD = 20;
    public static final int MIN_TIMEOUT_MINUTES = 1;
    /** Discord caps a timeout at 28 days. */
    public static final int MAX_TIMEOUT_MINUTES = 28 * 24 * 60;

    public enum Action { TIMEOUT, KICK, BAN }

    /** {@code timeoutMinutes} only matters for {@link Action#TIMEOUT}. */
    public record Rule(int threshold, Action action, int timeoutMinutes) {
        public Duration timeout() {
            return Duration.ofMinutes(timeoutMinutes);
        }

        /** "timed out for 1 hour", "kicked", "banned". */
        public String describe() {
            return switch (action) {
                case TIMEOUT -> "timed out for " + span(timeoutMinutes);
                case KICK -> "kicked";
                case BAN -> "banned";
            };
        }
    }

    private Autopunish() {}

    /** The rule that fires when a member reaches {@code warnings} warnings, if any. */
    public static Optional<Rule> forCount(List<Rule> rules, int warnings) {
        return rules.stream().filter(r -> r.threshold() == warnings).findFirst();
    }

    /** What is wrong with a set of rules, as words for the dashboard (empty when it can be saved). */
    public static List<String> problems(List<Rule> rules) {
        List<String> problems = new ArrayList<>();
        if (rules.size() > MAX_RULES) problems.add("A server can have at most " + MAX_RULES + " autopunish rules.");
        Set<Integer> seen = new HashSet<>();
        for (Rule rule : rules) {
            if (rule.threshold() < 1 || rule.threshold() > MAX_THRESHOLD) {
                problems.add("Warnings needed must be between 1 and " + MAX_THRESHOLD + ".");
            } else if (!seen.add(rule.threshold())) {
                problems.add("Two rules both fire at " + rule.threshold() + " warnings. Keep one.");
            }
            if (rule.action() == Action.TIMEOUT && (rule.timeoutMinutes() < MIN_TIMEOUT_MINUTES || rule.timeoutMinutes() > MAX_TIMEOUT_MINUTES)) {
                problems.add("A timeout must last between 1 minute and 28 days.");
            }
        }
        return problems;
    }

    private static String span(int minutes) {
        if (minutes % (24 * 60) == 0) {
            int days = minutes / (24 * 60);
            return days + (days == 1 ? " day" : " days");
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
