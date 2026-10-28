package com.runeclans.bot.events;

import com.runeclans.bot.events.PrizeRules.PrizeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the prizes a host defined and the final standings into who is owed what. A prize applies to a place or a range of places (a single
 * winner is just place 1; "1st to 3rd" is three places). When the event is scored by team, a place is a team and the prize is divided
 * among its members, given in full to each, or given to the captain, as the host chose. An amount is a fixed number or a share of the pot.
 * Pure, so it can be tested.
 */
public final class PrizePlan {
    /** How a team's prize reaches its members. */
    public enum Split { EQUAL, EACH, CAPTAIN }

    /** A prize as defined. {@code placeTo} ≥ {@code place}; {@code percentOfPot} is in basis points (10000 = the whole pot) and replaces {@code amount} when above zero. */
    public record Prize(long id, int place, int placeTo, PrizeKind kind, String description, long amount, int percentOfPot, Split split) {
        public Prize {
            description = description == null ? "" : description;
        }
    }

    /** A place in the final standings: a player, or a team with its members (captain first, then who joined first). */
    public record Place(String subject, List<Long> members) {}

    /** What one person is owed for one prize at one place. */
    public record Award(long prizeId, int place, long userId, PrizeKind kind, String description, long amount) {}

    private PrizePlan() {}

    /** What is wrong with a prize, in words, or an empty list. */
    public static List<String> problems(Prize p) {
        List<String> problems = new ArrayList<>(PrizeRules.prizeProblems(new PrizeRules.Prize(p.kind(), p.description(), p.amount(), p.place())));
        if (p.place() < 1) problems.add("Give the prize a place: 1 for first.");
        if (p.placeTo() < p.place() || p.placeTo() > 100) problems.add("The last place has to be the same as the first place or later (up to 100).");
        if (p.percentOfPot() < 0 || p.percentOfPot() > 10_000) problems.add("A share of the pot has to be between 0 and 100 percent.");
        if (p.percentOfPot() > 0 && p.kind() == PrizeKind.CLAN_POINTS) problems.add("A clan points prize can't be a share of a gp pot: give it a number of points.");
        if (p.kind() == PrizeKind.CLAN_POINTS && p.amount() > 1_000_000) problems.add("One clan points prize can be at most 1,000,000 points.");
        if (p.split() == null) problems.add("Choose how a team's prize is shared.");
        return problems.stream().distinct().toList();
    }

    /** Percent shares can't promise more than the whole pot between them. */
    public static List<String> setProblems(List<Prize> prizes) {
        List<String> problems = new ArrayList<>();
        if (prizes.size() > PrizeRules.MAX_PRIZES) problems.add("An event can have at most " + PrizeRules.MAX_PRIZES + " prizes.");
        long promised = 0;
        for (Prize p : prizes) {
            if (p.percentOfPot() > 0) promised += (long) p.percentOfPot() * (p.placeTo() - p.place() + 1);
        }
        if (promised > 10_000) problems.add("The prizes promise more than the whole pot (" + promised / 100 + "%).");
        return problems;
    }

    /** Who is owed what, given the prizes, the places in order (first place first) and the pot. Places with no prize, or beyond the standings, get nothing. */
    public static List<Award> plan(List<Prize> prizes, List<Place> standings, long pot) {
        List<Award> awards = new ArrayList<>();
        for (Prize prize : prizes) {
            for (int rank = prize.place(); rank <= prize.placeTo(); rank++) {
                if (rank > standings.size()) break;
                Place place = standings.get(rank - 1);
                long amount = prize.percentOfPot() > 0 ? pot * prize.percentOfPot() / 10_000 : prize.amount();
                List<Long> members = place.members();
                if (members.isEmpty()) continue;
                if (members.size() == 1 || prize.split() == Split.EACH) {
                    for (long member : members) awards.add(new Award(prize.id(), rank, member, prize.kind(), prize.description(), amount));
                } else if (prize.split() == Split.CAPTAIN) {
                    awards.add(new Award(prize.id(), rank, members.get(0), prize.kind(), prize.description(), amount));
                } else {
                    long each = amount / members.size();
                    long remainder = amount - each * members.size();
                    for (int i = 0; i < members.size(); i++) {
                        // the first member (the captain) takes the odd leftover, so nothing is lost
                        awards.add(new Award(prize.id(), rank, members.get(i), prize.kind(), prize.description(), each + (i == 0 ? remainder : 0)));
                    }
                }
            }
        }
        return awards;
    }
}
