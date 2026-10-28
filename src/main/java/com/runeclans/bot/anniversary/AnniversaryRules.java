package com.runeclans.bot.anniversary;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Clan anniversaries (T-194), the pure part: who reaches a whole number of years in the clan during a week, and whether this week's post is due.
 * "This week" is the post day and the six days after it. A member who joined on 29 February is celebrated on 28 February in other years.
 */
public final class AnniversaryRules {
    private AnniversaryRules() {}

    public static final int MAX_MIN_YEARS = 20;

    /** One member's anniversary in the week: {@code years} in the clan on {@code date}. */
    public record Anniversary(String rsn, int years, LocalDate date) {}

    /** The day in {@code year} that {@code joined}'s anniversary falls on. */
    static LocalDate anniversaryIn(LocalDate joined, int year) {
        MonthDay md = MonthDay.from(joined);
        if (md.equals(MonthDay.of(Month.FEBRUARY, 29)) && !java.time.Year.isLeap(year)) return LocalDate.of(year, 2, 28);
        return md.atYear(year);
    }

    /** The anniversary of {@code joined} within [{@code from}, {@code from}+6], if it is at least {@code minYears}; null otherwise. */
    public static Anniversary inWeek(String rsn, LocalDate joined, LocalDate from, int minYears) {
        if (joined == null) return null;
        LocalDate to = from.plusDays(6);
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            LocalDate day = anniversaryIn(joined, year);
            int years = year - joined.getYear();
            if (!day.isBefore(from) && !day.isAfter(to) && years >= Math.max(1, minYears)) return new Anniversary(rsn, years, day);
        }
        return null;
    }

    /** Everyone's anniversaries in the week starting {@code from}: most years first, then by day and name. */
    public static List<Anniversary> week(List<Member> members, LocalDate from, int minYears) {
        List<Anniversary> out = new ArrayList<>();
        for (Member m : members) {
            Anniversary a = inWeek(m.rsn(), m.joined(), from, minYears);
            if (a != null) out.add(a);
        }
        out.sort(Comparator.comparingInt(Anniversary::years).reversed().thenComparing(Anniversary::date).thenComparing(a -> a.rsn().toLowerCase()));
        return out;
    }

    /** A roster member and the day they joined the clan in game (null when unknown). */
    public record Member(String rsn, LocalDate joined) {}

    /** Whether the weekly post is due at {@code now} (UTC): the chosen weekday, at or after the chosen hour, and not already posted today. */
    public static boolean due(LocalDateTime now, DayOfWeek weekday, int hour, LocalDate lastPostedOn) {
        return now.getDayOfWeek() == weekday && now.getHour() >= hour && !now.toLocalDate().equals(lastPostedOn);
    }
}
