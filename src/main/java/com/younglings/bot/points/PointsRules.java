package com.younglings.bot.points;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules for a server's points (what some clans call tickets): who may be given how many, why, and what counts as a valid
 * name for them. Pure, so it can be tested. The ledger itself only ever grows: a mistake is corrected with an opposite entry, never an edit.
 */
public final class PointsRules {
    /** One entry can't move more than this, which catches a mistyped extra zero. */
    public static final long MAX_PER_ENTRY = 1_000_000;
    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 200;
    public static final int MAX_UNIT = 20;

    /** Where an entry came from, so a server can tell a manual award from an approved submission or a spent reward. */
    public enum Category { MANUAL, EVENT, SUBMISSION, DONATION, REDEMPTION, ADJUSTMENT, KEYWORD, PLUGIN }

    /** Where a submission stands. */
    public enum Status { PENDING, APPROVED, DENIED }

    /** Where a redemption stands: waiting for staff to hand the reward over, done, or cancelled (and refunded). */
    public enum Fulfilment { PENDING, FULFILLED, CANCELLED }

    public static final int MAX_REWARD_NAME = 60;
    public static final int MAX_REWARD_DESCRIPTION = 200;
    public static final int MAX_REWARDS = 25;
    public static final long MAX_STOCK = 100_000;

    public static final int MIN_DESCRIPTION = 3;
    public static final int MAX_DESCRIPTION = 300;
    public static final int MAX_NOTE = 200;
    /** A member can have this many submissions waiting at once. */
    public static final int MAX_PENDING_PER_MEMBER = 5;
    /** Proof is re-posted for staff, so it has to fit under the smallest upload limit a server has. */
    public static final long MAX_PROOF_BYTES = 8L * 1024 * 1024;

    private PointsRules() {}

    /** What is wrong with a submission, or null. Proof must be an image the bot can re-post for staff. */
    public static String submissionProblem(String description, String contentType, String fileName, long size, int pendingAlready) {
        String d = description == null ? "" : description.strip();
        if (d.length() < MIN_DESCRIPTION) return "Say what the screenshot shows, so staff know what to check.";
        if (d.length() > MAX_DESCRIPTION) return "Keep the description to " + MAX_DESCRIPTION + " characters or fewer.";
        boolean image = contentType != null ? contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                : fileName != null && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)$");
        if (!image) return "The proof has to be an image (a screenshot).";
        if (size > MAX_PROOF_BYTES) return "That image is too big. Keep it under 8 MB.";
        if (pendingAlready >= MAX_PENDING_PER_MEMBER) return "You already have " + MAX_PENDING_PER_MEMBER + " submissions waiting. Wait for staff to look at them first.";
        return null;
    }

    /** What is wrong with a reward, or null. A null {@code stock} means unlimited. */
    public static String rewardProblem(String name, String description, long cost, Long stock) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return "Give the reward a name.";
        if (n.length() > MAX_REWARD_NAME) return "Keep the name to " + MAX_REWARD_NAME + " characters or fewer.";
        if (description != null && description.strip().length() > MAX_REWARD_DESCRIPTION) return "Keep the description to " + MAX_REWARD_DESCRIPTION + " characters or fewer.";
        if (cost <= 0) return "A reward has to cost more than zero.";
        if (cost > MAX_PER_ENTRY) return "A reward can cost at most " + format(MAX_PER_ENTRY, "points") + ".";
        if (stock != null && (stock < 0 || stock > MAX_STOCK)) return "Stock has to be between 0 and " + MAX_STOCK + ", or left empty for unlimited.";
        return null;
    }

    /** What is wrong with a decision, or null. Approving needs a positive amount; denying needs a reason the member will be told. */
    public static String decisionProblem(Status status, long points, String note) {
        String n = note == null ? "" : note.strip();
        if (n.length() > MAX_NOTE) return "Keep the note to " + MAX_NOTE + " characters or fewer.";
        if (status == Status.APPROVED) {
            if (points <= 0) return "Approving needs a number of points above zero.";
            if (points > MAX_PER_ENTRY) return "One entry can move at most " + format(MAX_PER_ENTRY, "points") + ".";
            return null;
        }
        if (status == Status.DENIED) return n.isEmpty() ? "Say why it was denied, so the member can fix it next time." : null;
        return "A submission can only be approved or denied.";
    }

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
