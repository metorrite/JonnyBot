package com.younglings.bot.points;

import com.younglings.bot.points.PointsRepository.Settings;
import com.younglings.bot.points.PointsRules.Category;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.OptionalLong;

/** Gives and takes points, with the checks every route in (the command, the dashboard, later approvals) shares. */
@BService
public class PointsService {
    /** What happened to an attempt to move points. */
    public enum Outcome { DONE, INVALID, DUPLICATE, INSUFFICIENT }

    public record Result(Outcome outcome, String message, long balance, long entryId) {}

    private final PointsRepository repository;

    public PointsService(PointsRepository repository) {
        this.repository = repository;
    }

    /**
     * Moves points for a member. {@code sourceKey} makes it safe to repeat: the same key is only ever paid once in a server (pass null
     * for a one-off manual entry). A negative amount is refused when it would take the balance below zero.
     */
    public Result apply(long guildId, long userId, long points, String reason, Category category, String sourceKey, Long actorId) {
        String problem = PointsRules.awardProblem(points, reason);
        if (problem != null) return new Result(Outcome.INVALID, problem, repository.balance(guildId, userId), 0);
        String unit = repository.settings(guildId).unit();
        OptionalLong id = repository.add(guildId, userId, points, reason.strip(), category, sourceKey, actorId);
        long balance = repository.balance(guildId, userId);
        if (id.isPresent()) return new Result(Outcome.DONE, null, balance, id.getAsLong());
        if (sourceKey != null && points >= 0) return new Result(Outcome.DUPLICATE, "That has already been paid out.", balance, 0);
        if (points < 0 && PointsRules.overdraws(balance, points)) {
            return new Result(Outcome.INSUFFICIENT, "They only have " + PointsRules.format(balance, unit) + ", so " + PointsRules.format(-points, unit) + " can't be taken.", balance, 0);
        }
        return new Result(Outcome.DUPLICATE, "That has already been paid out.", balance, 0);
    }

    /** What happened to an attempt to decide a submission. */
    public enum DecisionOutcome { DONE, INVALID, NOT_FOUND, ALREADY_DECIDED }

    public record Decision(DecisionOutcome outcome, String message, PointsRepository.Submission submission, long balance) {}

    /**
     * Approves a pending submission, paying {@code points} to the member exactly once, or denies it with a note. The decision is claimed
     * first with a single conditional update (so two staff clicking at once can't both win) and the payout carries the key
     * {@code submission:<id>}, so even a repeat after a crash can't pay twice.
     */
    public Decision decide(long guildId, long submissionId, PointsRules.Status status, long points, String note, long actorId) {
        var found = repository.submission(guildId, submissionId);
        if (found.isEmpty()) return new Decision(DecisionOutcome.NOT_FOUND, "That submission doesn't exist.", null, 0);
        String problem = PointsRules.decisionProblem(status, points, note);
        if (problem != null) return new Decision(DecisionOutcome.INVALID, problem, found.get(), 0);
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        boolean approve = status == PointsRules.Status.APPROVED;
        if (!repository.decide(guildId, submissionId, status, actorId, trimmed, approve ? points : null)) {
            return new Decision(DecisionOutcome.ALREADY_DECIDED, "Someone has already decided that one.", found.get(), 0);
        }
        long userId = found.get().userId();
        if (approve) {
            repository.add(guildId, userId, points, "Approved submission: " + shorten(found.get().description()), Category.SUBMISSION, "submission:" + submissionId, actorId);
        }
        return new Decision(DecisionOutcome.DONE, null, repository.submission(guildId, submissionId).orElse(found.get()), repository.balance(guildId, userId));
    }

    private static String shorten(String s) {
        String t = s.strip();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    public Settings settings(long guildId) {
        return repository.settings(guildId);
    }
}
