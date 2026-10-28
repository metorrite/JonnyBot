package com.runeclans.bot.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clan seasons (T-596), the pure part: which season a day is in, and how a member's season score is worked out. Nothing here reads the
 * database, so the rules can be tested on their own.
 * <p>
 * <b>Seasons</b> are calendar quarters in UTC (Drake's planning answer): January to March, April to June, July to September and October to
 * December. A season's token is {@code 2026-q4}; its window is half-open, {@code [first day, first day of the next season)}.
 * <p>
 * <b>The score</b> is a view over data the bot already keeps (never a new ledger, so nothing is counted twice): XP gained on the clan
 * Hiscores, clan points awarded and events won inside the season. The three are in very different units (millions of XP, a few hundred
 * points, a handful of wins), so each is first turned into <em>rank points</em> from 0 to 100: everyone with more than zero in a category is
 * ranked, the best gets 100 and the rest step down evenly ({@code 100 × (n − rank + 1) / n}, ties share the better rank), and anyone with
 * nothing in it gets 0. The season score is the owner's weighted average of the three ({@code Σ weight × points / Σ weight}), so equal
 * weights really are equal: being top for XP is worth exactly as much as being top for clan points or event wins, and one member with a
 * giant XP total can't drown out the rest.
 */
public final class SeasonRules {
    private SeasonRules() {}

    /** The heaviest a single weight can be; 0 leaves a category out. */
    public static final int MAX_WEIGHT = 10;
    /** How many members get a season badge at the season's end. */
    public static final int BADGE_PLACES = 3;
    /** How many standings rows the public answer carries. */
    public static final int MAX_ROWS = 50;
    /** How many rows a finished season keeps. */
    public static final int MAX_KEPT_ROWS = 200;

    private static final Pattern TOKEN = Pattern.compile("(\\d{4})-q([1-4])");

    /** One season: a calendar quarter in UTC. */
    public record Season(int year, int quarter) {
        public Season {
            if (quarter < 1 || quarter > 4) throw new IllegalArgumentException("quarter " + quarter);
        }

        public String token() {
            return year + "-q" + quarter;
        }

        public LocalDate firstDay() {
            return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        }

        /** The first day of the next season (the window's open end). */
        public LocalDate endDay() {
            return firstDay().plusMonths(3);
        }

        public OffsetDateTime from() {
            return firstDay().atStartOfDay().atOffset(ZoneOffset.UTC);
        }

        public OffsetDateTime to() {
            return endDay().atStartOfDay().atOffset(ZoneOffset.UTC);
        }

        /** "Oct–Dec 2026". */
        public String label() {
            LocalDate first = firstDay();
            LocalDate last = endDay().minusDays(1);
            return first.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + "–" + last.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;
        }

        /** The recap period token the website's wrapped recap asks for ({@link RecapPeriod}'s inclusive day range). */
        public String recapPeriod() {
            return firstDay() + "_" + endDay().minusDays(1);
        }

        public Season previous() {
            return quarter == 1 ? new Season(year - 1, 4) : new Season(year, quarter - 1);
        }

        public long days() {
            return java.time.temporal.ChronoUnit.DAYS.between(firstDay(), endDay());
        }

        /** Whole days left from {@code now} until the season ends, counting a started day as a day (0 once it is over). */
        public long daysLeft(OffsetDateTime now) {
            long seconds = java.time.Duration.between(now, to()).getSeconds();
            return seconds <= 0 ? 0 : (seconds + 86_399) / 86_400;
        }
    }

    /** The season {@code at} falls in. */
    public static Season seasonOf(OffsetDateTime at) {
        LocalDate day = at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        return new Season(day.getYear(), (day.getMonthValue() - 1) / 3 + 1);
    }

    /** A season token ({@code 2026-q4}), or null for anything else. */
    public static Season parse(String token) {
        if (token == null) return null;
        Matcher m = TOKEN.matcher(token.strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        return year < 2000 || year > 2999 ? null : new Season(year, Integer.parseInt(m.group(2)));
    }

    // ---------- weights ----------

    /** How much each category counts, 0 to {@link #MAX_WEIGHT}; at least one is above 0. */
    public record Weights(int xp, int points, int events) {
        public static final Weights EQUAL = new Weights(1, 1, 1);

        public int total() {
            return xp + points + events;
        }
    }

    /** What is wrong with these weights, or null when they can be saved. */
    public static String weightsProblem(int xp, int points, int events) {
        for (int w : new int[] {xp, points, events}) {
            if (w < 0 || w > MAX_WEIGHT) return "Each weight is a whole number from 0 to " + MAX_WEIGHT + ".";
        }
        if (xp + points + events == 0) return "At least one of XP, clan points and event wins has to count.";
        return null;
    }

    // ---------- the score ----------

    /** One member's season so far: XP gained, clan points awarded and events won inside the season. */
    public record Input(String rsn, long xp, long points, int wins) {}

    /** One row of the standings. {@code score} has one decimal; the {@code *Pts} are the rank points (0 to 100) each category gave. */
    public record Row(int place, String rsn, double score, long xp, long points, int wins, int xpPts, int pointsPts, int winsPts) {}

    /**
     * Rank points per member for one category, in the order given: everyone above zero is ranked (ties share the better rank) and gets
     * {@code round(100 × (n − rank + 1) / n)}; zero or less gets 0.
     */
    static int[] rankPoints(long[] values) {
        int n = 0;
        for (long v : values) if (v > 0) n++;
        int[] out = new int[values.length];
        if (n == 0) return out;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= 0) continue;
            int better = 0;
            for (long v : values) if (v > values[i]) better++;
            int rank = better + 1;
            out[i] = (int) Math.round(100.0 * (n - rank + 1) / n);
        }
        return out;
    }

    /**
     * The standings: everyone with a score above 0, best first (ties on score go to more XP, then the name, and share a place). The score is
     * the weighted average of the rank points, one decimal.
     */
    public static List<Row> standings(List<Input> inputs, Weights weights) {
        int size = inputs.size();
        long[] xp = new long[size];
        long[] points = new long[size];
        long[] wins = new long[size];
        for (int i = 0; i < size; i++) {
            xp[i] = inputs.get(i).xp();
            points[i] = inputs.get(i).points();
            wins[i] = inputs.get(i).wins();
        }
        int[] xpPts = rankPoints(xp);
        int[] pointsPts = rankPoints(points);
        int[] winsPts = rankPoints(wins);
        int total = Math.max(1, weights.total());

        record Scored(Input in, double score, int xpPts, int pointsPts, int winsPts) {}
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            double raw = (weights.xp() * xpPts[i] + weights.points() * pointsPts[i] + weights.events() * winsPts[i]) / (double) total;
            double score = Math.round(raw * 10) / 10.0;
            if (score > 0) scored.add(new Scored(inputs.get(i), score, xpPts[i], pointsPts[i], winsPts[i]));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingLong((Scored s) -> s.in().xp()).reversed())
                .thenComparing(s -> s.in().rsn().toLowerCase(Locale.ROOT)));

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            int place = i > 0 && scored.get(i - 1).score() == s.score() ? rows.get(i - 1).place() : i + 1;
            rows.add(new Row(place, s.in().rsn(), s.score(), Math.max(0, s.in().xp()), Math.max(0, s.in().points()), Math.max(0, s.in().wins()),
                    s.xpPts(), s.pointsPts(), s.winsPts()));
        }
        return rows;
    }

    /** The badge a finished season's place earns, or null outside the top {@link #BADGE_PLACES}. */
    public static String badgeTitle(int place) {
        return switch (place) {
            case 1 -> "Season champion";
            case 2 -> "Season runner-up";
            case 3 -> "Season third place";
            default -> null;
        };
    }

    /** The season-end post (no mentions are allowed when it is sent): the podium and where to see the rest. */
    public static String postText(Season season, List<Row> podium) {
        StringBuilder text = new StringBuilder("**The " + season.label() + " season is over!**");
        if (podium.isEmpty()) return text.append("\nNobody scored this season. A new one starts now.").toString();
        String[] medals = {"🥇", "🥈", "🥉"};
        for (Row r : podium) {
            if (r.place() > BADGE_PLACES) break;
            text.append('\n').append(medals[r.place() - 1]).append(' ').append(r.rsn()).append(" — ").append(scoreText(r.score()));
        }
        return text.append("\nThe full standings and the season recap are on the clan website. A new season starts now.").toString();
    }

    static String scoreText(double score) {
        return score == Math.rint(score) ? Long.toString((long) score) : String.format(Locale.ROOT, "%.1f", score);
    }
}
