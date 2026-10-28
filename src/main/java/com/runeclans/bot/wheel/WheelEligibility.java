package com.runeclans.bot.wheel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Who gets an entry on the Citadel wheel. One entry per <i>person</i>, not per account: someone who capped on two accounts linked to the same Discord
 * user has one. Staff don't take part: a person is left out when they are an admin in Discord, or when any account linked to them holds an admin (or
 * higher) clan rank, and that covers all of their linked accounts, so an admin's alts get no entries either. Pure, so it can be tested.
 */
public final class WheelEligibility {
    /** A name that gets one entry, and the accounts that capped for it when there was more than one. */
    public record Person(String name, List<String> accounts) {}

    /** Someone who capped but isn't on the wheel, and why. */
    public record Left(String name, String reason) {}

    public record Result(List<String> names, List<Left> leftOut, List<Person> merged) {}

    /** A confirmed account link: this RuneScape name belongs to this Discord user. */
    public record Link(String rsn, long discordUserId) {}

    private WheelEligibility() {}

    /**
     * @param cappers   the accounts that capped, in the order the report lists them
     * @param rankByRsn each clan member's rank name, by lower-case RuneScape name
     * @param adminRank whether a rank name is the admin rank or higher
     * @param links     every account link in the server
     * @param discordAdmin whether a Discord user is an admin there
     */
    public static Result select(List<String> cappers, Map<String, String> rankByRsn, Predicate<String> adminRank, List<Link> links, LongPredicate discordAdmin) {
        Map<String, Long> userByRsn = new LinkedHashMap<>();
        Map<Long, List<String>> accountsByUser = new LinkedHashMap<>();
        for (Link link : links) {
            userByRsn.put(link.rsn().toLowerCase(Locale.ROOT), link.discordUserId());
            accountsByUser.computeIfAbsent(link.discordUserId(), k -> new ArrayList<>()).add(link.rsn());
        }

        // one group per person, in the order they first appear
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, Long> groupUser = new LinkedHashMap<>();
        for (String capper : cappers) {
            Long user = userByRsn.get(capper.toLowerCase(Locale.ROOT));
            String key = user != null ? "user:" + user : "rsn:" + capper.toLowerCase(Locale.ROOT);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(capper);
            if (user != null) groupUser.put(key, user);
        }

        List<String> names = new ArrayList<>();
        List<Left> leftOut = new ArrayList<>();
        List<Person> merged = new ArrayList<>();
        for (var group : groups.entrySet()) {
            List<String> capped = group.getValue();
            Long user = groupUser.get(group.getKey());
            String reason = null;
            if (user != null && discordAdmin.test(user)) reason = "Discord admin";
            if (reason == null) {
                List<String> accounts = new ArrayList<>(capped);
                if (user != null) for (String linked : accountsByUser.getOrDefault(user, List.of())) if (accounts.stream().noneMatch(a -> a.equalsIgnoreCase(linked))) accounts.add(linked);
                for (String account : accounts) {
                    String rank = rankByRsn.get(account.toLowerCase(Locale.ROOT));
                    if (rank != null && adminRank.test(rank)) {
                        reason = capped.stream().anyMatch(c -> c.equalsIgnoreCase(account)) ? "clan rank " + rank : "linked to " + account + " (clan rank " + rank + ")";
                        break;
                    }
                }
            }
            if (reason != null) {
                leftOut.add(new Left(String.join(" + ", capped), reason));
            } else {
                names.add(capped.get(0));
                if (capped.size() > 1) merged.add(new Person(capped.get(0), List.copyOf(capped)));
            }
        }
        return new Result(List.copyOf(names), List.copyOf(leftOut), List.copyOf(merged));
    }

    /** A predicate for "this rank is {@code adminRankName} or higher" given the ranks in ascending order. Falls back to the standard RuneScape ranks when the list has no such rank. */
    public static Predicate<String> atLeast(List<String> ranksAscending, String adminRankName) {
        List<String> ranks = ranksAscending.stream().map(r -> r.toLowerCase(Locale.ROOT)).toList();
        int threshold = ranks.indexOf(adminRankName.toLowerCase(Locale.ROOT));
        if (threshold < 0) {
            ranks = List.of("recruit", "corporal", "sergeant", "lieutenant", "captain", "general", "admin", "organiser", "coordinator", "overseer", "deputy owner", "owner");
            threshold = ranks.indexOf(adminRankName.toLowerCase(Locale.ROOT));
        }
        List<String> order = ranks;
        int min = threshold;
        return rank -> {
            int at = order.indexOf(rank.toLowerCase(Locale.ROOT));
            return min >= 0 && at >= min;
        };
    }
}
