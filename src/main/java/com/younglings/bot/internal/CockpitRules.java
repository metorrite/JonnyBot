package com.younglings.bot.internal;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The site admin's Clan health page (T-593, the "officer cockpit"), the pure part: who goes on which list. The facts come from the bot's own
 * tables ({@link SiteCockpitStore}): the clan roster from the clan Hiscores, its joins and leaves, the Citadel cap weeks already stored, and
 * who is linked in the server. Nothing here asks RuneMetrics; the stored cap weeks come from members' Adventurer's Logs, so a week where
 * nobody in the clan capped is treated as "no data" (an outage, a private clan, or that feed stopping) rather than as everyone missing it.
 */
public final class CockpitRules {
    private CockpitRules() {}

    /** "Gone quiet": no XP on the clan Hiscores for this many days. Drake's planning answer: 14, which the site's owner can change. */
    public static final int DEFAULT_QUIET_DAYS = 14;
    public static final int MIN_QUIET_DAYS = 7;
    public static final int MAX_QUIET_DAYS = 90;
    /** "Missed caps": at least {@link #CAP_MISSES} of the last {@link #CAP_WEEKS} finished Citadel weeks (Drake's planning answer). */
    public static final int CAP_WEEKS = 4;
    public static final int CAP_MISSES = 2;
    /** "New joins not linked": joined in the last this many days. */
    public static final int NEW_JOIN_DAYS = 30;
    /** "Left this week": the last seven days. */
    public static final int LEFT_DAYS = 7;
    /** A "done" mark shows for this long, and only for the same streak (a member who went quiet again needs a new look). */
    public static final int MARK_DAYS = 14;
    /** The clan Hiscores not read for this long: the page says the quiet list is paused (it can't grow while nobody reads them). */
    public static final int ROSTER_STALE_DAYS = 3;

    /** The lists, by the key the website sends back with a mark. */
    public static final List<String> KINDS = List.of("quiet", "caps", "unlinked", "left");

    /** One active roster member. {@code xpWatchedSince} null: the bot hasn't compared their XP total yet. {@code discordId} null: not linked here. */
    public record Member(String rsn, String rank, OffsetDateTime firstSeen, OffsetDateTime lastSeen, OffsetDateTime xpChangedAt,
                         OffsetDateTime xpWatchedSince, Long discordId) {
        String key() {
            return keyOf(rsn);
        }
    }

    /** A JOIN or LEAVE the daily roster sync recorded. */
    public record RosterEvent(String rsn, String type, OffsetDateTime at) {}

    /** A rename the roster sync suspects ({@code PENDING}) or that staff confirmed ({@code CONFIRMED}). */
    public record Rename(String oldRsn, String newRsn, String status) {}

    /** One Citadel week a member visited or capped in (as {@code SiteStatsRepository.citadelGrid} answers). */
    public record CapWeek(String rsn, LocalDate weekStart, boolean capped) {}

    /** Someone on staff marked a row done. */
    public record Mark(String kind, String rsnKey, long by, String byName, OffsetDateTime at) {}

    public static String keyOf(String rsn) {
        return rsn == null ? "" : rsn.strip().toLowerCase(Locale.ROOT);
    }

    /** The quiet threshold a site asked for, held to the allowed range; null or nonsense is the default. */
    public static int quietDays(Integer asked) {
        if (asked == null) return DEFAULT_QUIET_DAYS;
        return Math.max(MIN_QUIET_DAYS, Math.min(MAX_QUIET_DAYS, asked));
    }

    // ---------- gone quiet ----------

    /**
     * {@code days}: how long the clan Hiscores showed no change in their XP total, measured up to the last time the roster was read for them
     * (so a roster sync that stopped never makes everyone look quiet). {@code atLeast}: no change was ever seen, so it is the time since the
     * bot started comparing.
     */
    public record Quiet(Member member, OffsetDateTime lastXpAt, long days, boolean atLeast) {}

    public static List<Quiet> quiet(List<Member> members, int quietDays) {
        List<Quiet> out = new ArrayList<>();
        for (Member m : members) {
            if (m.xpWatchedSince() == null || m.lastSeen() == null) continue;
            boolean atLeast = m.xpChangedAt() == null;
            OffsetDateTime last = atLeast ? m.xpWatchedSince() : m.xpChangedAt();
            long days = Duration.between(last, m.lastSeen()).toDays();
            if (days >= quietDays) out.add(new Quiet(m, last, days, atLeast));
        }
        out.sort(Comparator.comparingLong(Quiet::days).reversed().thenComparing(q -> q.member().key()));
        return out;
    }

    /** When the bot started comparing XP totals for this clan (the earliest member it watches), or null before the first sync. */
    public static OffsetDateTime watchStarted(List<Member> members) {
        return members.stream().map(Member::xpWatchedSince).filter(t -> t != null).min(Comparator.naturalOrder()).orElse(null);
    }

    /** The newest roster read, or null when there is no roster. */
    public static OffsetDateTime rosterCheckedAt(List<Member> members) {
        return members.stream().map(Member::lastSeen).filter(t -> t != null).max(Comparator.naturalOrder()).orElse(null);
    }

    public static boolean rosterStale(OffsetDateTime checkedAt, OffsetDateTime now) {
        return checkedAt != null && Duration.between(checkedAt, now).toDays() >= ROSTER_STALE_DAYS;
    }

    // ---------- missed caps ----------

    /** One counted week: {@code hasData} false when nobody in the clan capped that week (left out of everyone's count). */
    public record Week(LocalDate start, boolean hasData) {}

    /** A member's weeks, oldest first: true capped, false missed, null not counted (no data, or they weren't in the clan yet). */
    public record Missed(Member member, List<Boolean> weeks, int missed) {}

    /** {@code noLog}: members left out because the bot has no Adventurer's Log entry of theirs in the window (a private profile, most likely). */
    public record Caps(List<Week> weeks, List<Missed> rows, int noLog) {
        public boolean anyData() {
            return weeks.stream().anyMatch(Week::hasData);
        }
    }

    /** The last {@link #CAP_WEEKS} finished Citadel weeks, oldest first, before the week that started on {@code thisWeekStart}. */
    public static List<LocalDate> countedWeeks(LocalDate thisWeekStart) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = CAP_WEEKS; i >= 1; i--) out.add(thisWeekStart.minusWeeks(i));
        return out;
    }

    public static Caps caps(List<Member> members, List<CapWeek> grid, Map<String, OffsetDateTime> lastLog, LocalDate thisWeekStart) {
        List<LocalDate> starts = countedWeeks(thisWeekStart);
        Map<String, Set<LocalDate>> capped = new HashMap<>();
        Set<LocalDate> withData = new HashSet<>();
        for (CapWeek w : grid) {
            if (!w.capped()) continue;
            capped.computeIfAbsent(keyOf(w.rsn()), k -> new HashSet<>()).add(w.weekStart());
            withData.add(w.weekStart());
        }
        List<Week> weeks = starts.stream().map(s -> new Week(s, withData.contains(s))).toList();
        OffsetDateTime windowStart = starts.getFirst().atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Missed> rows = new ArrayList<>();
        int noLog = 0;
        if (withData.isEmpty()) return new Caps(weeks, rows, 0);
        for (Member m : members) {
            OffsetDateTime log = lastLog.get(m.key());
            if (log == null || log.isBefore(windowStart)) {
                noLog++;
                continue;
            }
            Set<LocalDate> mine = capped.getOrDefault(m.key(), Set.of());
            List<Boolean> states = new ArrayList<>();
            int missed = 0;
            for (Week w : weeks) {
                boolean member = m.firstSeen() == null || !m.firstSeen().toLocalDate().isAfter(w.start());
                if (!w.hasData() || !member) {
                    states.add(null);
                    continue;
                }
                boolean did = mine.contains(w.start());
                states.add(did);
                if (!did) missed++;
            }
            if (missed >= CAP_MISSES) rows.add(new Missed(m, states, missed));
        }
        rows.sort(Comparator.comparingInt(Missed::missed).reversed().thenComparing(r -> r.member().key()));
        return new Caps(weeks, rows, noLog);
    }

    // ---------- new joins not linked ----------

    public record Joiner(Member member, OffsetDateTime joinedAt) {}

    /** Members who joined in the last {@link #NEW_JOIN_DAYS} days (a recorded JOIN) and have no Discord account linked here, newest first. */
    public static List<Joiner> unlinked(List<Member> members, List<RosterEvent> events, OffsetDateTime now) {
        OffsetDateTime from = now.minusDays(NEW_JOIN_DAYS);
        Map<String, OffsetDateTime> joined = newest(events, "JOIN", from);
        List<Joiner> out = new ArrayList<>();
        for (Member m : members) {
            OffsetDateTime at = joined.get(m.key());
            if (at != null && m.discordId() == null) out.add(new Joiner(m, at));
        }
        out.sort(Comparator.comparing(Joiner::joinedAt).reversed().thenComparing(j -> j.member().key()));
        return out;
    }

    // ---------- left this week ----------

    /** {@code maybeRenamedTo}: the roster sync suspects a rename to that name (staff haven't confirmed it). */
    public record Leaver(String rsn, OffsetDateTime leftAt, Long discordId, String maybeRenamedTo) {}

    /**
     * LEAVEs of the last {@link #LEFT_DAYS} days, newest first, except anyone back in the roster and any confirmed rename (they didn't leave).
     * {@code links}: Discord ids by name key, for every name linked in the server (a leaver's link stays).
     */
    public static List<Leaver> left(List<Member> members, List<RosterEvent> events, List<Rename> renames, Map<String, Long> links, OffsetDateTime now) {
        Set<String> active = new HashSet<>();
        for (Member m : members) active.add(m.key());
        Map<String, String> pending = new HashMap<>();
        Set<String> confirmed = new HashSet<>();
        for (Rename r : renames) {
            if ("CONFIRMED".equals(r.status())) confirmed.add(keyOf(r.oldRsn()));
            else if ("PENDING".equals(r.status())) pending.putIfAbsent(keyOf(r.oldRsn()), r.newRsn());
        }
        Map<String, String> names = new HashMap<>();
        for (RosterEvent e : events) names.putIfAbsent(keyOf(e.rsn()), e.rsn());
        List<Leaver> out = new ArrayList<>();
        newest(events, "LEAVE", now.minusDays(LEFT_DAYS)).forEach((key, at) -> {
            if (active.contains(key) || confirmed.contains(key)) return;
            out.add(new Leaver(names.get(key), at, links.get(key), pending.get(key)));
        });
        out.sort(Comparator.comparing(Leaver::leftAt).reversed().thenComparing(l -> keyOf(l.rsn())));
        return out;
    }

    /** The newest event of {@code type} per name key at or after {@code from}. */
    private static Map<String, OffsetDateTime> newest(List<RosterEvent> events, String type, OffsetDateTime from) {
        Map<String, OffsetDateTime> out = new LinkedHashMap<>();
        for (RosterEvent e : events) {
            if (!type.equals(e.type()) || e.at() == null || e.at().isBefore(from)) continue;
            out.merge(keyOf(e.rsn()), e.at(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    // ---------- done marks ----------

    /**
     * The mark that still counts for a row, or null: younger than {@link #MARK_DAYS}, and made since {@code since} (the start of what the row
     * is about: the quiet streak, the join, the leave), so a member who goes quiet again shows as new.
     */
    public static Mark activeMark(Map<String, Mark> marks, String kind, String key, OffsetDateTime since, OffsetDateTime now) {
        Mark m = marks.get(kind + ":" + key);
        if (m == null || m.at() == null) return null;
        if (m.at().isBefore(now.minusDays(MARK_DAYS))) return null;
        if (since != null && m.at().isBefore(since)) return null;
        return m;
    }

    /** Marks by {@code kind:key}, as {@link #activeMark} reads them. */
    public static Map<String, Mark> byKey(List<Mark> marks) {
        Map<String, Mark> out = new HashMap<>();
        for (Mark m : marks) out.put(m.kind() + ":" + m.rsnKey(), m);
        return out;
    }

    /** A RuneScape name as a mark may name it: 1 to 12 letters, digits, spaces, hyphens or underscores. */
    public static boolean plausibleName(String rsn) {
        return rsn != null && rsn.strip().matches("[A-Za-z0-9 _-]{1,12}");
    }
}
