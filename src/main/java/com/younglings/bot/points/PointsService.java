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

    public Settings settings(long guildId) {
        return repository.settings(guildId);
    }
}
