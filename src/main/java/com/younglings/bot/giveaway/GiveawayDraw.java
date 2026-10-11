package com.younglings.bot.giveaway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** The draw and the words around it, apart from Discord so they can be tested. */
public final class GiveawayDraw {
    private GiveawayDraw() {}

    /** Up to {@code count} different winners chosen at random from {@code entrants}, never anyone in {@code exclude}. */
    public static List<Long> pick(Collection<Long> entrants, int count, Set<Long> exclude, Random random) {
        List<Long> pool = new ArrayList<>(entrants.stream().distinct().filter(id -> !exclude.contains(id)).toList());
        List<Long> winners = new ArrayList<>();
        while (winners.size() < count && !pool.isEmpty()) winners.add(pool.remove(random.nextInt(pool.size())));
        return winners;
    }

    /** "<@1>, <@2> and <@3>" */
    public static String mentions(List<Long> ids) {
        List<String> m = ids.stream().map(id -> "<@" + id + ">").toList();
        if (m.size() <= 1) return String.join("", m);
        return String.join(", ", m.subList(0, m.size() - 1)) + " and " + m.get(m.size() - 1);
    }

    public static String enterLabel(int entries) {
        return entries == 0 ? "Enter" : "Enter (" + entries + ")";
    }

    public static String buttonId(long giveawayId) {
        return "gw:" + giveawayId;
    }
}
