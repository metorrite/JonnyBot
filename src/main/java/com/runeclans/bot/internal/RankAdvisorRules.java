package com.runeclans.bot.internal;

import com.runeclans.bot.internal.CockpitRules.CapWeek;
import com.runeclans.bot.runescape.ClanPointsRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rank-up advisor on the site admin's Clan health page (T-594), the pure part: the site owner's rules per clan rank, and who meets the
 * rules for their next rank. It only advises: Jagex gives no way to change an in-game rank, so staff rank the member in game and tick them off
 * here; a tick hides them until they meet the rules for the rank after.
 * <p>
 * The facts are the bot's own: the clan Hiscores roster (rank, and the XP total the bot saw when the member reached their current rank), the
 * days since they joined, the stored Citadel cap weeks (from members' Adventurer's Logs, like the Clan health caps list) and the clan points
 * ledger. Nothing here asks RuneMetrics.
 */
public final class RankAdvisorRules {
    private RankAdvisorRules() {}

    /** The in-game clan ranks, lowest to highest (the clan Hiscores always use Jagex's names). */
    public static final List<String> LADDER = ClanPointsRepository.STANDARD_RANK_NAMES;
    /** Ranks a rule may be set for: everything above Recruit, below Owner (nobody is promoted to Recruit, and the owner rank is handed over). */
    public static final List<String> TARGETS = LADDER.subList(1, LADDER.size() - 1);
    /** Citadel caps count over this many Citadel weeks, this week included. */
    public static final int CAP_WINDOW_WEEKS = 12;
    public static final long MAX_XP = 50_000_000_000L;
    public static final int MAX_DAYS = 3650;
    public static final long MAX_POINTS = 1_000_000_000L;
    /** Ticks show under "Ticked off recently" this long; kept for {@link #TICK_KEEP_DAYS} so the member stays hidden until the game shows the rank. */
    public static final int TICK_SHOW_DAYS = 14;
    public static final int TICK_KEEP_DAYS = 120;
    /** The congrats message: at most this long, with {@code {name}} and {@code {rank}} filled in. */
    public static final int MAX_CONGRATS = 300;
    public static final String DEFAULT_CONGRATS = "Congratulations {name} on your promotion to {rank}!";

    /** One rank's rule; a null field isn't checked. A rule with nothing set is no rule. */
    public record Rule(String rank, Long minXp, Integer minDays, Integer minCaps, Long minPoints) {
        public boolean empty() {
            return minXp == null && minDays == null && minCaps == null && minPoints == null;
        }
    }

    /**
     * One roster member's facts. {@code xpGained}: XP on the clan Hiscores since the bot saw them reach their current rank (null before it has
     * a starting point). {@code joined}: when they joined the clan (staff-set join date, else when the roster first showed them). {@code caps}:
     * capped weeks in the window, null when the bot has no Adventurer's Log entry of theirs in it (a private profile, most likely).
     */
    public record Facts(String rsn, String rank, Long xpGained, OffsetDateTime rankSince, OffsetDateTime joined, Integer caps, long points) {
        String key() {
            return CockpitRules.keyOf(rsn);
        }
    }

    /** A staff tick: {@code rsnKey} was ranked to {@code rank} in game (as staff say). */
    public record Tick(String rsnKey, String rank, long by, String byName, OffsetDateTime at) {}

    /**
     * One part of a rule: what it needs, what the member has (null: unknown), and whether it is met. {@code unavailable}: the bot can't count
     * this part for anyone right now (Citadel caps once RuneMetrics is off, T-725), so it is left out of the count instead of failing it.
     */
    public record Check(String what, long need, Long have, boolean met, boolean unavailable) {
        public Check(String what, long need, Long have, boolean met) {
            this(what, need, have, met, false);
        }

        /** Whether this part is counted at all (an unavailable part is skipped). */
        public boolean counts() {
            return !unavailable;
        }
    }

    /** A member who meets every part of the rule for {@code target}. */
    public record Ready(Facts member, String target, List<Check> checks) {}

    /** The ladder position of a rank name (any case), or -1 for a name that isn't an in-game rank. */
    public static int indexOf(String rank) {
        if (rank == null) return -1;
        String k = rank.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < LADDER.size(); i++) if (LADDER.get(i).toLowerCase(Locale.ROOT).equals(k)) return i;
        return -1;
    }

    /** The rank name as the ladder spells it, or null. */
    public static String canonical(String rank) {
        int i = indexOf(rank);
        return i < 0 ? null : LADDER.get(i);
    }

    public static boolean isTarget(String rank) {
        String c = canonical(rank);
        return c != null && TARGETS.contains(c);
    }

    public static long daysSince(OffsetDateTime from, OffsetDateTime now) {
        return from == null ? 0 : Math.max(0, java.time.Duration.between(from, now).toDays());
    }

    /** Every part the rule sets, checked against the member, with Citadel caps counted. */
    public static List<Check> check(Rule rule, Facts m, OffsetDateTime now) {
        return check(rule, m, now, true);
    }

    /**
     * Every part the rule sets, checked against the member. {@code capsAvailable} false (RuneMetrics is off, {@link CitadelCaps}): a caps part
     * is marked unavailable and not counted, so ranks still work on XP, days and points. The owner's {@code minCaps} stays saved for when caps
     * return.
     */
    public static List<Check> check(Rule rule, Facts m, OffsetDateTime now, boolean capsAvailable) {
        List<Check> out = new ArrayList<>();
        if (rule.minXp() != null) out.add(new Check("xp", rule.minXp(), m.xpGained(), m.xpGained() != null && m.xpGained() >= rule.minXp()));
        if (rule.minDays() != null) {
            Long days = m.joined() == null ? null : daysSince(m.joined(), now);
            out.add(new Check("days", rule.minDays(), days, days != null && days >= rule.minDays()));
        }
        if (rule.minCaps() != null && !capsAvailable) out.add(new Check("caps", rule.minCaps(), null, false, true));
        else if (rule.minCaps() != null) {
            Long caps = m.caps() == null ? null : (long) m.caps();
            out.add(new Check("caps", rule.minCaps(), caps, caps != null && caps >= rule.minCaps()));
        }
        if (rule.minPoints() != null) out.add(new Check("points", rule.minPoints(), m.points(), m.points() >= rule.minPoints()));
        return out;
    }

    /**
     * Who meets the rules for their next rank. The next rank is the one above their in-game rank, or above the highest rank staff ticked them
     * off for that the game doesn't show yet (a tick hides them until they meet the rule for the rank after). A rank with no rule lists nobody.
     * Highest target first, then the longest waiting at their rank.
     */
    public static List<Ready> ready(List<Facts> members, Map<String, Rule> rules, List<Tick> ticks, OffsetDateTime now) {
        return ready(members, rules, ticks, now, true);
    }

    /**
     * As {@link #ready(List, Map, List, OffsetDateTime)}; with {@code capsAvailable} false a caps part isn't counted, and a rule whose only
     * part is caps lists nobody (it has nothing left to check, and listing the whole rank would be wrong).
     */
    public static List<Ready> ready(List<Facts> members, Map<String, Rule> rules, List<Tick> ticks, OffsetDateTime now, boolean capsAvailable) {
        Map<String, Set<Integer>> ticked = new HashMap<>();
        for (Tick t : ticks) {
            int i = indexOf(t.rank());
            if (i >= 0) ticked.computeIfAbsent(t.rsnKey(), k -> new HashSet<>()).add(i);
        }
        List<Ready> out = new ArrayList<>();
        for (Facts m : members) {
            int at = indexOf(m.rank());
            if (at < 0) continue;
            int target = at + 1;
            Set<Integer> mine = ticked.getOrDefault(m.key(), Set.of());
            while (mine.contains(target)) target++;
            if (target >= LADDER.size() || !TARGETS.contains(LADDER.get(target))) continue;
            Rule rule = rules.get(LADDER.get(target));
            if (rule == null || rule.empty()) continue;
            // Ticked off for a rank the game doesn't show yet: the XP counted so far was gained at the old rank, so it can't count toward
            // the rank after. XP at the new rank starts once the roster shows it (until then it is unknown, so an XP rule isn't met).
            Facts counted = target > at + 1 && m.xpGained() != null
                    ? new Facts(m.rsn(), m.rank(), null, m.rankSince(), m.joined(), m.caps(), m.points()) : m;
            List<Check> checks = check(rule, counted, now, capsAvailable);
            if (checks.stream().noneMatch(Check::counts)) continue;
            if (checks.stream().filter(Check::counts).allMatch(Check::met)) out.add(new Ready(m, LADDER.get(target), checks));
        }
        out.sort(Comparator.comparingInt((Ready r) -> indexOf(r.target())).reversed()
                .thenComparing(r -> r.member().rankSince() == null ? OffsetDateTime.MAX : r.member().rankSince())
                .thenComparing(r -> r.member().key()));
        return out;
    }

    /** Capped weeks per member key over the window (this Citadel week and the {@link #CAP_WINDOW_WEEKS} - 1 before it). */
    public static Map<String, Integer> capsByMember(List<CapWeek> grid, LocalDate thisWeekStart) {
        LocalDate from = thisWeekStart.minusWeeks(CAP_WINDOW_WEEKS - 1L);
        Map<String, Set<LocalDate>> weeks = new HashMap<>();
        for (CapWeek w : grid) {
            if (!w.capped() || w.weekStart().isBefore(from)) continue;
            weeks.computeIfAbsent(CockpitRules.keyOf(w.rsn()), k -> new HashSet<>()).add(w.weekStart());
        }
        Map<String, Integer> out = new HashMap<>();
        weeks.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /** A member's caps in the window, or null when the bot has no Adventurer's Log entry of theirs since the window started. */
    public static Integer capsOf(String rsnKey, Map<String, Integer> capped, Map<String, OffsetDateTime> lastLog, LocalDate thisWeekStart) {
        OffsetDateTime windowStart = thisWeekStart.minusWeeks(CAP_WINDOW_WEEKS - 1L).atStartOfDay().atOffset(ZoneOffset.UTC);
        Integer caps = capped.get(rsnKey);
        if (caps != null) return caps;
        OffsetDateTime log = lastLog.get(rsnKey);
        return log == null || log.isBefore(windowStart) ? null : 0;
    }

    // ---------- the owner's edits ----------

    /** The congrats text as saved: trimmed, no control characters but new lines, at most {@link #MAX_CONGRATS}; blank is the default. */
    public static String cleanCongrats(String raw) {
        String s = raw == null ? "" : raw.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
        if (s.isEmpty()) return DEFAULT_CONGRATS;
        return s.length() > MAX_CONGRATS ? s.substring(0, MAX_CONGRATS) : s;
    }

    /** The message posted for one member: their RuneScape name and new rank in place of {@code {name}} and {@code {rank}}. */
    public static String congratsText(String template, String rsn, String rank) {
        String t = template == null || template.isBlank() ? DEFAULT_CONGRATS : template;
        return t.replace("{name}", rsn).replace("{rank}", rank);
    }

    /** A rule part's value from the website: null (not checked) or a whole number in range; anything else is a problem in words. */
    static Long number(Object raw, long max, String what, List<String> problems) {
        if (raw == null) return null;
        if (raw instanceof Number n && (n instanceof Integer || n instanceof Long || n instanceof Short) && n.longValue() >= 0 && n.longValue() <= max) {
            return n.longValue();
        }
        problems.add(what + " must be a whole number from 0 to " + String.format(Locale.ROOT, "%,d", max) + ".");
        return null;
    }
}
