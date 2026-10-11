package com.younglings.bot.points;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules for a server's points (what Eternal Rising calls tickets): who may be given how many, why, and what counts as a valid
 * name for them. Pure, so it can be tested. The ledger itself only ever grows: a mistake is corrected with an opposite entry, never an edit.
 */
public final class PointsRules {
    /** One entry can't move more than this, which catches a mistyped extra zero. */
    public static final long MAX_PER_ENTRY = 1_000_000;
    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 200;
    public static final int MAX_UNIT = 20;

    /** Where an entry came from, so a server can tell a manual award from an approved submission or a spent reward. */
    public enum Category { MANUAL, EVENT, SUBMISSION, DONATION, REDEMPTION, ADJUSTMENT }

    private PointsRules() {}

    /** What is wrong with an award of {@code points} for {@code reason}, in words, or null when it is fine. */
    public static String awardProblem(long points, String reason) {
        if (points == 0) return "An award can't be zero points.";
        if (Math.abs(points) > MAX_PER_ENTRY) return "One entry can move at most " + format(MAX_PER_ENTRY, "points") + ".";
        String r = reason == null ? "" : reason.strip();
        if (r.length() < MIN_REASON) return "Give a reason, so the history makes sense later.";
        if (r.length() > MAX_REASON) return "Keep the reason to " + MAX_REASON + " characters or fewer.";
        return null;
    }

    /** Whether taking {@code delta} from a balance of {@code balance} would overdraw it. Giving points never does. */
    public static boolean overdraws(long balance, long delta) {
        return balance + delta < 0;
    }

    /** "1,250 points", "1 ticket" (a single unit loses its trailing s). */
    public static String format(long amount, String unit) {
        String name = unit == null || unit.isBlank() ? "points" : unit.strip();
        String shown = Math.abs(amount) == 1 && name.endsWith("s") && name.length() > 1 ? name.substring(0, name.length() - 1) : name;
        return String.format(Locale.ROOT, "%,d", amount) + " " + shown;
    }

    /** Why a name for the points can't be used, or null. One short word or two, letters and spaces. */
    public static String unitProblem(String unit) {
        if (unit == null || unit.strip().isEmpty()) return "Name the points, e.g. points or tickets.";
        if (unit.strip().length() > MAX_UNIT) return "The name can be at most " + MAX_UNIT + " characters.";
        if (!unit.strip().matches("[\\p{L}][\\p{L} '-]*")) return "The name can only use letters, spaces, apostrophes and dashes.";
        return null;
    }

    /** What is wrong with a settings change, as words. */
    public static List<String> settingsProblems(String unit) {
        List<String> problems = new ArrayList<>();
        String problem = unitProblem(unit);
        if (problem != null) problems.add(problem);
        return problems;
    }
}
