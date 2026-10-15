package com.younglings.bot.events;

import java.util.List;
import java.util.Set;

/**
 * Prizes and buy-ins: what kinds there are, the stages an award or a buy-in can be in, which moves are allowed, and how the pot is added up.
 * The host decides what the prizes are; the engine only keeps an honest record of who was paid, whose prize was revoked or refunded, and
 * what has come in. Pure, so it can be tested.
 */
public final class PrizeRules {
    public static final int MAX_PRIZES = 25;
    public static final int MAX_DESCRIPTION = 200;
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    /** In-game (an item or gp, written out), clan points (paid through the points ledger), or something else the host describes. */
    public enum PrizeKind { IN_GAME, CLAN_POINTS, OTHER }

    /** An award starts pending; paying it, revoking it (given then taken back) and refunding it are separate, recorded steps. */
    public enum AwardStatus { PENDING, PAID, REVOKED, REFUNDED }

    /** A buy-in is due until it is paid, and can be refunded after. */
    public enum BuyInStatus { NONE, DUE, PAID, REFUNDED }

    /** What a buy-in costs and how it is held. {@code amount} is in {@code kind}'s own unit (gp, points, or just a count for other). */
    public record BuyIn(boolean enabled, PrizeKind kind, long amount, boolean showPot) {}

    /** A prize as the host defines it. {@code place} is 1 for first and so on; 0 means it isn't tied to a place. */
    public record Prize(PrizeKind kind, String description, long amount, int place) {}

    private PrizeRules() {}

    public static Set<AwardStatus> nextAward(AwardStatus from) {
        return switch (from) {
            case PENDING -> Set.of(AwardStatus.PAID, AwardStatus.REVOKED);
            case PAID -> Set.of(AwardStatus.REVOKED, AwardStatus.REFUNDED);
            case REVOKED, REFUNDED -> Set.of();
        };
    }

    public static boolean canMoveAward(AwardStatus from, AwardStatus to) {
        return nextAward(from).contains(to);
    }

    public static Set<BuyInStatus> nextBuyIn(BuyInStatus from) {
        return switch (from) {
            case NONE -> Set.of(BuyInStatus.DUE);
            case DUE -> Set.of(BuyInStatus.PAID, BuyInStatus.NONE);
            case PAID -> Set.of(BuyInStatus.REFUNDED);
            case REFUNDED -> Set.of();
        };
    }

    public static boolean canMoveBuyIn(BuyInStatus from, BuyInStatus to) {
        return nextBuyIn(from).contains(to);
    }

    /** What is wrong with a prize, in words, or an empty list. */
    public static List<String> prizeProblems(Prize p) {
        List<String> problems = new java.util.ArrayList<>();
        String d = p.description() == null ? "" : p.description().strip();
        if (p.kind() == null) problems.add("Choose what kind of prize it is.");
        if (p.kind() == PrizeKind.CLAN_POINTS) {
            if (p.amount() < 1 || p.amount() > MAX_AMOUNT) problems.add("A clan points prize needs a number of points above zero.");
        } else if (d.isEmpty()) {
            problems.add("Describe the prize, for example \"Zaros godsword\" or \"50M gp\".");
        }
        if (d.length() > MAX_DESCRIPTION) problems.add("Keep the prize description to " + MAX_DESCRIPTION + " characters or fewer.");
        if (p.amount() < 0) problems.add("A prize can't have a negative amount.");
        if (p.place() < 0 || p.place() > 100) problems.add("The place has to be between 1 and 100, or 0 for no place.");
        return problems;
    }

    public static List<String> buyInProblems(BuyIn b) {
        List<String> problems = new java.util.ArrayList<>();
        if (!b.enabled()) return problems;
        if (b.kind() == null) problems.add("Choose what the buy-in is paid in.");
        if (b.amount() < 1 || b.amount() > MAX_AMOUNT) problems.add("The buy-in has to be above zero.");
        return problems;
    }

    /** The pot: everything paid in, less what was refunded. Zero when buy-ins are off. */
    public static long pot(BuyIn buyIn, List<BuyInStatus> players) {
        if (!buyIn.enabled()) return 0;
        long paid = players.stream().filter(s -> s == BuyInStatus.PAID).count();
        return paid * buyIn.amount();
    }

    /** Whether the pot may be shown to players: only when the host switched it on and buy-ins exist. */
    public static boolean potVisible(BuyIn buyIn) {
        return buyIn.enabled() && buyIn.showPot();
    }
}
