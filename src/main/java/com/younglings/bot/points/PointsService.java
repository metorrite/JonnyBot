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
    private final RewardsRepository rewards;
    private final java.util.List<java.util.function.BiConsumer<Long, Long>> afterPointsAdded = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PointsService(PointsRepository repository, RewardsRepository rewards) {
        this.repository = repository;
        this.rewards = rewards;
    }

    /**
     * Asks to be told (guild id, user id) after an entry that gave a member points was recorded, whichever route it came by (the command, the
     * dashboard, an approved submission, an event prize, a keyword). The listener must not throw and should not block.
     */
    public void onPointsAdded(java.util.function.BiConsumer<Long, Long> listener) {
        afterPointsAdded.add(listener);
    }

    private void pointsAdded(long guildId, long userId) {
        for (var listener : afterPointsAdded) {
            try {
                listener.accept(guildId, userId);
            } catch (RuntimeException e) {
                // a points entry never fails because something reacting to it did
            }
        }
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
        if (id.isPresent()) {
            if (points > 0) pointsAdded(guildId, userId);
            return new Result(Outcome.DONE, null, balance, id.getAsLong());
        }
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
            pointsAdded(guildId, userId);
        }
        return new Decision(DecisionOutcome.DONE, null, repository.submission(guildId, submissionId).orElse(found.get()), repository.balance(guildId, userId));
    }

    /** What happened to an attempt to redeem or handle a redemption. */
    public enum RedeemOutcome { DONE, NOT_FOUND, SOLD_OUT, INSUFFICIENT, ALREADY_HANDLED }

    public record Redeemed(RedeemOutcome outcome, String message, RewardsRepository.Redemption redemption, long balance) {}

    /**
     * Spends a member's points on a reward. Stock is claimed first (one conditional update), the redemption recorded, then the points taken
     * as a negative ledger entry keyed {@code redemption:<id>}, which the ledger refuses if it would overdraw. If any step after the stock
     * claim fails, the earlier steps are undone, so nobody loses points or stock for a redemption that did not happen.
     */
    public Redeemed redeem(long guildId, long userId, long rewardId) {
        var reward = rewards.reward(guildId, rewardId);
        if (reward.isEmpty() || !reward.get().active()) return new Redeemed(RedeemOutcome.NOT_FOUND, "There's no such reward.", null, repository.balance(guildId, userId));
        String unit = repository.settings(guildId).unit();
        if (repository.balance(guildId, userId) < reward.get().cost()) {
            return new Redeemed(RedeemOutcome.INSUFFICIENT, "That costs " + PointsRules.format(reward.get().cost(), unit) + " and you have " + PointsRules.format(repository.balance(guildId, userId), unit) + ".", null, repository.balance(guildId, userId));
        }
        var cost = rewards.claimStock(guildId, rewardId);
        if (cost.isEmpty()) return new Redeemed(RedeemOutcome.SOLD_OUT, "That reward is sold out.", null, repository.balance(guildId, userId));
        long id = rewards.addRedemption(guildId, reward.get(), userId, cost.getAsLong());
        var paid = repository.add(guildId, userId, -cost.getAsLong(), "Reward: " + reward.get().name(), Category.REDEMPTION, "redemption:" + id, null);
        if (paid.isEmpty()) {
            rewards.deleteRedemption(id);
            rewards.returnStock(guildId, rewardId);
            return new Redeemed(RedeemOutcome.INSUFFICIENT, "You don't have enough " + unit + " for that.", null, repository.balance(guildId, userId));
        }
        return new Redeemed(RedeemOutcome.DONE, null, rewards.redemption(guildId, id).orElse(null), repository.balance(guildId, userId));
    }

    /** Marks a redemption handed over, or cancels it, which refunds the points (once, keyed {@code redemption-refund:<id>}) and returns the stock. */
    public Redeemed handle(long guildId, long redemptionId, boolean fulfil, long actorId) {
        var found = rewards.redemption(guildId, redemptionId);
        if (found.isEmpty()) return new Redeemed(RedeemOutcome.NOT_FOUND, "That redemption doesn't exist.", null, 0);
        var status = fulfil ? PointsRules.Fulfilment.FULFILLED : PointsRules.Fulfilment.CANCELLED;
        if (!rewards.handle(guildId, redemptionId, status, actorId)) return new Redeemed(RedeemOutcome.ALREADY_HANDLED, "Someone has already handled that one.", found.get(), 0);
        if (!fulfil) {
            repository.add(guildId, found.get().userId(), found.get().cost(), "Refund: " + found.get().rewardName(), Category.ADJUSTMENT, "redemption-refund:" + redemptionId, actorId);
            rewards.returnStock(guildId, found.get().rewardId());
            pointsAdded(guildId, found.get().userId());
        }
        return new Redeemed(RedeemOutcome.DONE, null, rewards.redemption(guildId, redemptionId).orElse(found.get()), repository.balance(guildId, found.get().userId()));
    }

    private static String shorten(String s) {
        String t = s.strip();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    public Settings settings(long guildId) {
        return repository.settings(guildId);
    }
}
