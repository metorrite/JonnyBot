package com.younglings.bot.points;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for role rewards (a role handed out when a member's points reach a number) and keyword triggers ("thanks @someone" gives them a
 * point). Pure, so it can be tested; the services and listener only carry out what this decides.
 */
public final class PointsExtrasRules {
    private PointsExtrasRules() {}

    // ---------- role rewards ----------

    public static final int MAX_ROLE_REWARDS = 25;
    public static final long MAX_THRESHOLD = 10_000_000;

    /** At {@code threshold} points the member is given {@code roleId}; with {@code removeLower} the roles of the lower rewards are taken away then. */
    public record RoleReward(long id, long guildId, long threshold, long roleId, boolean removeLower) {}

    /** What to do for a member: roles to add and to take away, and the rewards now counted as given (never given again). */
    public record RolePlan(List<RoleReward> granted, Set<Long> addRoles, Set<Long> removeRoles) {
        public boolean none() {
            return granted.isEmpty();
        }
    }

    /**
     * The roles a member's balance earns. A reward is given once ({@code alreadyGranted} are never given again, so a role staff took away
     * stays gone) and never taken back because the balance fell later (spending points on a reward must not cost the member a role). With
     * {@code removeLower}, giving a reward also takes away the roles of the rewards below it. Rewards are given in order of threshold.
     */
    public static RolePlan planRoles(List<RoleReward> all, long balance, Set<Long> alreadyGranted, Set<Long> heldRoles) {
        List<RoleReward> sorted = all.stream().sorted(java.util.Comparator.comparingLong(RoleReward::threshold)).toList();
        List<RoleReward> granted = new ArrayList<>();
        Set<Long> add = new LinkedHashSet<>();
        Set<Long> remove = new LinkedHashSet<>();
        for (RoleReward reward : sorted) {
            if (reward.threshold() > balance || alreadyGranted.contains(reward.id())) continue;
            granted.add(reward);
            add.add(reward.roleId());
            remove.remove(reward.roleId());
            if (reward.removeLower()) {
                for (RoleReward lower : sorted) {
                    if (lower.threshold() < reward.threshold() && lower.roleId() != reward.roleId()) {
                        add.remove(lower.roleId());
                        remove.add(lower.roleId());
                    }
                }
            }
        }
        add.removeAll(heldRoles);
        remove.retainAll(heldRoles);
        remove.removeAll(add);
        return new RolePlan(granted, add, remove);
    }

    /** What is wrong with a reward added to {@code existing}, as words, or an empty list. {@code existing} must not contain the reward being changed. */
    public static List<String> roleRewardProblems(long threshold, long roleId, List<RoleReward> existing) {
        List<String> problems = new ArrayList<>();
        if (threshold < 1 || threshold > MAX_THRESHOLD) problems.add("The number of points has to be between 1 and " + String.format(Locale.ROOT, "%,d", MAX_THRESHOLD) + ".");
        if (existing.size() >= MAX_ROLE_REWARDS) problems.add("A server can have at most " + MAX_ROLE_REWARDS + " role rewards.");
        if (existing.stream().anyMatch(r -> r.threshold() == threshold)) problems.add("Another role reward already starts at that number of points.");
        if (existing.stream().anyMatch(r -> r.roleId() == roleId)) problems.add("That role is already given by another reward.");
        return problems;
    }

    // ---------- keyword triggers ----------

    public static final int MAX_TRIGGERS = 10;
    public static final int MAX_KEYWORD = 40;
    public static final int MIN_KEYWORD = 2;
    public static final int MAX_TRIGGER_POINTS = 10;
    public static final int MAX_COOLDOWN_SECONDS = 3600;
    public static final int MAX_DAILY_CAP = 50;
    /** One message can pay at most this many people, so a message that mentions a crowd is not a way to hand out points. */
    public static final int MAX_RECEIVERS = 3;

    /**
     * Saying {@code keyword} while mentioning someone gives them {@code points}. {@code cooldownSeconds}: the same person can't set the
     * same trigger off again that soon. {@code dailyCap}: the most times one member can be paid by this trigger in a day.
     */
    public record Trigger(long id, long guildId, String keyword, int points, int cooldownSeconds, int dailyCap, boolean enabled) {}

    /** Lower-cases and collapses whitespace in a typed keyword. */
    public static String cleanKeyword(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** What is wrong with a trigger, as words, or an empty list. {@code existing} must not contain the trigger being changed. */
    public static List<String> triggerProblems(String keyword, int points, int cooldownSeconds, int dailyCap, List<Trigger> existing) {
        List<String> problems = new ArrayList<>();
        String k = cleanKeyword(keyword);
        if (k.length() < MIN_KEYWORD || k.length() > MAX_KEYWORD) problems.add("The word or phrase has to be " + MIN_KEYWORD + " to " + MAX_KEYWORD + " characters.");
        else if (!k.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} '!?-]*")) problems.add("The word or phrase can use letters, numbers, spaces, apostrophes, dashes, ! and ?.");
        if (points < 1 || points > MAX_TRIGGER_POINTS) problems.add("A trigger gives between 1 and " + MAX_TRIGGER_POINTS + " points.");
        if (cooldownSeconds < 0 || cooldownSeconds > MAX_COOLDOWN_SECONDS) problems.add("The cool-down has to be between 0 and " + MAX_COOLDOWN_SECONDS + " seconds.");
        if (dailyCap < 1 || dailyCap > MAX_DAILY_CAP) problems.add("A member can be paid by one trigger between 1 and " + MAX_DAILY_CAP + " times a day.");
        if (existing.size() >= MAX_TRIGGERS) problems.add("A server can have at most " + MAX_TRIGGERS + " triggers.");
        if (existing.stream().anyMatch(t -> t.keyword().equals(k))) problems.add("There is already a trigger for that word or phrase.");
        return problems;
    }

    /** Whether the keyword appears in the text as a whole word or phrase ("thanks" is not found in "thanksgiving"). */
    public static boolean matches(String keyword, String text) {
        if (keyword == null || keyword.isBlank() || text == null) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword.toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}])").matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * Who is paid for a message: the people it mentions who are real members, not bots and not the writer, each once, at most {@link #MAX_RECEIVERS}.
     * {@code mentioned} is the ids of the members mentioned, in order; {@code bots} the ids among them that are bots.
     */
    public static List<Long> receivers(long writer, List<Long> mentioned, Set<Long> bots) {
        List<Long> out = new ArrayList<>();
        for (long id : mentioned) {
            if (id == writer || bots.contains(id) || out.contains(id)) continue;
            out.add(id);
            if (out.size() == MAX_RECEIVERS) break;
        }
        return out;
    }

    /**
     * The ledger key of one payment. The ledger refuses a key it has already paid, so the same writer thanking the same member with the same
     * trigger on the same day pays once, however many times it is said.
     */
    public static String key(long triggerId, long writer, long receiver, LocalDate day) {
        return "kw:" + triggerId + ":" + writer + ":" + receiver + ":" + day;
    }

    /** A SQL LIKE pattern for all the payments one member received from a trigger on a day, whoever wrote them. */
    public static String receivedPattern(long triggerId, long receiver, LocalDate day) {
        return "kw:" + triggerId + ":%:" + receiver + ":" + day;
    }

    /** Remembers when each writer last set each trigger off, to enforce the cool-down. Kept in memory; a restart only forgives the cool-down. */
    public static final class Cooldowns {
        private final Map<String, Long> last = new HashMap<>();

        /** Whether the writer may use the trigger now; if so, records the use. */
        public synchronized boolean tryUse(long nowMillis, long writer, long triggerId, int cooldownSeconds) {
            String k = writer + ":" + triggerId;
            Long before = last.get(k);
            if (before != null && nowMillis - before < cooldownSeconds * 1000L) return false;
            last.put(k, nowMillis);
            if (last.size() > 20_000) last.values().removeIf(t -> nowMillis - t > MAX_COOLDOWN_SECONDS * 1000L);
            return true;
        }
    }
}
