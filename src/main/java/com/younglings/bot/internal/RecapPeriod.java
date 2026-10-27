package com.younglings.bot.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A window of time a recap covers, worked out from the short token that appears in the website's URL.
 * <ul>
 *   <li>{@code week}, {@code month}, {@code year} — the current Citadel week (Wednesday to Tuesday), calendar
 *       month or calendar year, <em>to date</em>;</li>
 *   <li>{@code last-week}, {@code last-month}, {@code last-year} — the previous complete one;</li>
 *   <li>{@code 2026} or {@code 2026-09} — a specific year or month, however long ago;</li>
 *   <li>{@code mtd}/{@code ytd} (same as month/year), {@code last-7d}…{@code last-365d} (rolling), and {@code 2026-09-01_2026-09-20} (custom range);</li>
 *   <li>{@code all} — everything since tracking began (the caller supplies that start).</li>
 * </ul>
 * All windows are UTC and half-open: {@code [from, to)}. A window that is still running is cut off at "now".
 * <p>
 * {@code end} is where the window ends once it is complete: {@code to} for a complete window, the end of the Citadel week, month, year or
 * custom range for one still running, and null for a rolling window and {@code all}, which never complete. {@link #key()} uses it.
 */
record RecapPeriod(String token, String label, OffsetDateTime from, OffsetDateTime to, boolean toDate, OffsetDateTime end) {

    /** {@code last-7d}, {@code last-30d}, {@code last-90d}: a rolling window ending now. */
    private static final Pattern ROLLING = Pattern.compile("last-(7|14|30|60|90|180|365)d");
    /** {@code 2026-09-01_2026-09-20}: an explicit inclusive range of days (a single day is the same date twice). */
    private static final Pattern RANGE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d{4}-\\d{2}-\\d{2})");

    /** The Wednesday that starts the Citadel week containing {@code date}. */
    static LocalDate citadelWeekStart(LocalDate date) {
        int sinceWednesday = (date.getDayOfWeek().getValue() - DayOfWeek.WEDNESDAY.getValue() + 7) % 7;
        return date.minusDays(sinceWednesday);
    }

    private static OffsetDateTime start(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /**
     * @param trackingStart when the bot first had data; used only for {@code all}
     * @return the window, or empty if the token isn't understood or lies wholly in the future
     */
    static Optional<RecapPeriod> parse(String raw, OffsetDateTime now, OffsetDateTime trackingStart) {
        if (raw == null) return Optional.empty();
        String token = raw.trim().toLowerCase(Locale.ROOT);
        LocalDate today = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        if (token.equals("mtd")) token = "month";
        if (token.equals("ytd")) token = "year";

        Matcher rolling = ROLLING.matcher(token);
        if (rolling.matches()) {
            int days = Integer.parseInt(rolling.group(1));
            return Optional.of(running(token, "Last " + days + " days", now.minusDays(days), now, null));
        }
        Matcher range = RANGE.matcher(token);
        if (range.matches()) {
            try {
                LocalDate first = LocalDate.parse(range.group(1));
                LocalDate last = LocalDate.parse(range.group(2));
                if (last.isBefore(first) || first.isAfter(today)) return Optional.empty();
                OffsetDateTime to = start(last.plusDays(1));
                String label = first.equals(last) ? first.toString() : first + " to " + last;
                return Optional.of(to.isAfter(now) ? running(token, label, start(first), now, to) : complete(token, label, start(first), to));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        switch (token) {
            case "week" -> {
                LocalDate thisWeek = citadelWeekStart(today);
                return Optional.of(running(token, "This week so far", start(thisWeek), now, start(thisWeek.plusWeeks(1))));
            }
            case "last-week" -> {
                LocalDate thisWeek = citadelWeekStart(today);
                LocalDate from = thisWeek.minusWeeks(1);
                return Optional.of(complete(token, "Last week (" + shortRange(from, thisWeek.minusDays(1)) + ")", start(from), start(thisWeek)));
            }
            case "month" -> {
                YearMonth month = YearMonth.from(today);
                return Optional.of(running(token, monthName(month) + " so far", start(month.atDay(1)), now, start(month.plusMonths(1).atDay(1))));
            }
            case "last-month" -> {
                YearMonth last = YearMonth.from(today).minusMonths(1);
                return Optional.of(complete(token, monthName(last), start(last.atDay(1)), start(last.plusMonths(1).atDay(1))));
            }
            case "year" -> {
                int year = today.getYear();
                return Optional.of(running(token, year + " so far", start(LocalDate.of(year, 1, 1)), now, start(LocalDate.of(year + 1, 1, 1))));
            }
            case "last-year" -> {
                int year = today.getYear() - 1;
                return Optional.of(complete(token, String.valueOf(year), start(LocalDate.of(year, 1, 1)), start(LocalDate.of(year + 1, 1, 1))));
            }
            case "all" -> {
                OffsetDateTime from = trackingStart != null ? trackingStart : now.minusDays(1);
                return Optional.of(running(token, "Since we started tracking", from, now, null));
            }
            default -> {
                // fall through to explicit year / month below
            }
        }

        try {
            if (token.matches("\\d{4}")) {
                int year = Integer.parseInt(token);
                OffsetDateTime from = start(LocalDate.of(year, 1, 1));
                if (from.isAfter(now)) return Optional.empty();
                OffsetDateTime to = start(LocalDate.of(year + 1, 1, 1));
                return Optional.of(to.isAfter(now) ? running(token, year + " so far", from, now, to) : complete(token, String.valueOf(year), from, to));
            }
            if (token.matches("\\d{4}-\\d{2}")) {
                YearMonth month = YearMonth.parse(token);
                OffsetDateTime from = start(month.atDay(1));
                if (from.isAfter(now)) return Optional.empty();
                OffsetDateTime to = start(month.plusMonths(1).atDay(1));
                return Optional.of(to.isAfter(now) ? running(token, monthName(month) + " so far", from, now, to) : complete(token, monthName(month), from, to));
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static RecapPeriod running(String token, String label, OffsetDateTime from, OffsetDateTime now, OffsetDateTime end) {
        return new RecapPeriod(token, label, from, now, true, end);
    }

    private static RecapPeriod complete(String token, String label, OffsetDateTime from, OffsetDateTime to) {
        return new RecapPeriod(token, label, from, to, false, to);
    }

    /**
     * Which recap this is, for "has the member opened it yet" (a member's recap is private until they have, {@link RecapGate}): keyed on the
     * window, never the token, so every token for the same window gets the same key ({@code last-month}, {@code 2026-09} and
     * {@code 2026-09-01_2026-09-30} are one recap).
     * <ul>
     *   <li>a whole Citadel week, calendar month or calendar year: {@code week:<from>:<state>}, {@code month:<YYYY-MM>:<state>},
     *   {@code year:<YYYY>:<state>}, where the state is {@code running} while the window is still open and {@code final} once it ended, so
     *   opening October while it runs doesn't open the finished October;</li>
     *   <li>a rolling window: {@code rolling:<N>d}, and everything: {@code all} (no date: once the member opened theirs, it follows their
     *   choice);</li>
     *   <li>any other custom range: none, and that recap stays the member's own.</li>
     * </ul>
     */
    Optional<String> key() {
        if (token.equals("all")) return Optional.of("all");
        Matcher rolling = ROLLING.matcher(token);
        if (rolling.matches()) return Optional.of("rolling:" + rolling.group(1) + "d");
        if (end == null || !atMidnight(from) || !atMidnight(end)) return Optional.empty();
        LocalDate first = from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        LocalDate after = end.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        String state = toDate ? "running" : "final";
        if (citadelWeekStart(first).equals(first) && after.equals(first.plusWeeks(1))) return Optional.of("week:" + first + ":" + state);
        if (first.getDayOfMonth() == 1 && after.equals(first.plusMonths(1))) return Optional.of("month:" + YearMonth.from(first) + ":" + state);
        if (first.getDayOfYear() == 1 && after.equals(first.plusYears(1))) return Optional.of("year:" + first.getYear() + ":" + state);
        return Optional.empty();
    }

    /**
     * {@link #key()} of the window a recap answer was built for, from its {@code period} ({@code token}, {@code from}, {@code to},
     * {@code toDate}): the token is read again as of the answer's own {@code to}, so a cached answer keeps its window's key even after the
     * window rolls over. Empty when the answer doesn't describe a window this token gives (nothing is guessed).
     */
    static Optional<String> key(String token, OffsetDateTime from, OffsetDateTime to, boolean toDate) {
        if (token == null || from == null || to == null) return Optional.empty();
        return parse(token, to, from)
                .filter(p -> p.from().isEqual(from) && p.to().isEqual(to) && p.toDate() == toDate)
                .flatMap(RecapPeriod::key);
    }

    private static boolean atMidnight(OffsetDateTime at) {
        return at.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
    }

    private static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
    }

    private static String shortRange(LocalDate from, LocalDate to) {
        return from.getDayOfMonth() + " " + from.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " – "
                + to.getDayOfMonth() + " " + to.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** Whole days the window spans (at least 1). */
    long days() {
        return Math.max(1, java.time.Duration.between(from, to).toDays());
    }
}
