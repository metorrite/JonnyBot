package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Entry;
import com.younglings.bot.giveaway.GiveawayDraw;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * How an event is won (T-649), from {@code config.win}. Today an event is won on points: the standings decide the places and the prizes follow the places.
 * An organiser can instead have the bot draw winners among everyone who qualified (one entry per finished tile, or one per player or team), or give the
 * places in the order teams or players first finished a line or the whole board. An event that never saved this is won on points exactly as before.
 * Pure, so it can be tested.
 * <p>
 * The draw reuses the giveaway's fair pick ({@link GiveawayDraw#pick}) with a {@link Random} seeded from a number the bot chooses and shows, over the
 * entries in a fixed order (subject keys sorted, each subject's tickets together). Anyone with the seed and the entry list gets the same winners
 * ({@link #draw}), which is how staff can show the draw was fair.
 */
public final class WinModes {
    /** How the places are decided. */
    public enum Mode {
        /** The standings, most points first (today's behaviour, the default). */
        POINTS,
        /** A bot draw among everyone who qualified. */
        DRAW,
        /** The order teams or players first finished a line (a row, column, diagonal or shape group with a bonus). Bingo only. */
        FIRST_LINE,
        /** The order teams or players first finished every tile. Bingo only. */
        FIRST_BLACKOUT
    }

    /** How many entries a qualifier gets in the draw. */
    public enum Entries {
        /** One entry per finished tile, so more tiles = better odds (the default). */
        PER_TILE,
        /** One entry each, whatever they finished. */
        PER_PLAYER
    }

    public static final int MAX_MIN_TILES = 1000;
    public static final int MAX_WINNERS = 25;
    /** The bot only draws on its own if the end time passed less than this long ago (a bot that was down catches up, but never draws old events). */
    public static final Duration AUTO_WINDOW = Duration.ofDays(2);
    static final int MAX_POST = 1900;

    /**
     * {@code minTiles}: finished tiles a team or player needs to be in the draw. {@code winners}: how many the draw picks (places 1 to N). {@code autoDraw}:
     * the bot draws at the event's end time by itself (staff can always press "Draw now").
     */
    public record Settings(Mode mode, Entries entries, int minTiles, int winners, boolean autoDraw) {
        public boolean isDefault() {
            return equals(DEFAULTS);
        }

        public boolean draws() {
            return mode == Mode.DRAW;
        }
    }

    public static final Settings DEFAULTS = new Settings(Mode.POINTS, Entries.PER_TILE, 1, 1, true);

    /** One team or player in the draw: how many tiles they finished and how many entries that gives them. */
    public record Entrant(String subject, int tiles, int tickets) {}

    /**
     * One draw the bot made. {@code place} is null for a full draw (it picked {@code picked} for places 1, 2, ...) or the place a reroll filled, with
     * {@code replaced} the winner who didn't claim it. {@code by} is null when the bot drew by itself at the end time.
     */
    public record Round(int round, long seed, List<Entrant> entrants, List<String> picked, Integer place, String replaced, Instant at, Long by) {
        public Round {
            entrants = List.copyOf(entrants);
            picked = List.copyOf(picked);
        }

        public boolean reroll() {
            return place != null;
        }

        public int tickets() {
            return entrants.stream().mapToInt(Entrant::tickets).sum();
        }
    }

    private WinModes() {}

    // ---------- settings ----------

    /** The win rules of an event; anything missing or unreadable is the default (points), so a broken setting never changes who wins by surprise. */
    public static Settings of(DataObject config) {
        try {
            if (config == null || !config.hasKey("win") || config.isNull("win")) return DEFAULTS;
            return read(config.getObject("win"));
        } catch (RuntimeException e) {
            return DEFAULTS;
        }
    }

    /** What is wrong with {@code win} as an organiser sent it, for an event that is ({@code bingo}) or isn't a bingo; empty when it can be saved. */
    public static List<String> problems(DataObject win, boolean bingo) {
        List<String> problems = new ArrayList<>();
        if (win == null) return List.of("Send the win rules.");
        try {
            Mode mode = win.hasKey("mode") && !win.isNull("mode") ? mode(win.getString("mode")) : Mode.POINTS;
            if (mode == null) problems.add("Choose how the event is won: points, a prize draw, first to a line or first to a blackout.");
            if (!bingo && (mode == Mode.FIRST_LINE || mode == Mode.FIRST_BLACKOUT)) problems.add("First to a line or a blackout needs a bingo board.");
            if (win.hasKey("entries") && !win.isNull("entries") && entries(win.getString("entries")) == null) {
                problems.add("Choose one entry per finished tile or one entry per player.");
            }
            if (win.hasKey("minTiles") && !win.isNull("minTiles")) {
                long n = number(win, "minTiles");
                if (n < 1 || n > MAX_MIN_TILES) problems.add("To be in the draw a player needs between 1 and " + MAX_MIN_TILES + " finished tiles.");
            }
            if (win.hasKey("winners") && !win.isNull("winners")) {
                long n = number(win, "winners");
                if (n < 1 || n > MAX_WINNERS) problems.add("The draw can pick 1 to " + MAX_WINNERS + " winners.");
            }
            if (win.hasKey("autoDraw") && !win.isNull("autoDraw") && !(win.get("autoDraw") instanceof Boolean)) problems.add("Drawing at the end time is on or off.");
        } catch (RuntimeException e) {
            problems.add("The win rules couldn't be read.");
        }
        return problems.stream().distinct().toList();
    }

    /** Reads saved or sent rules (check {@link #problems} first); an unreadable part is its default. */
    public static Settings read(DataObject w) {
        Mode mode = w.hasKey("mode") && !w.isNull("mode") ? mode(w.getString("mode")) : null;
        Entries entries = w.hasKey("entries") && !w.isNull("entries") ? entries(w.getString("entries")) : null;
        int min = (int) clamp(w, "minTiles", DEFAULTS.minTiles(), 1, MAX_MIN_TILES);
        int winners = (int) clamp(w, "winners", DEFAULTS.winners(), 1, MAX_WINNERS);
        boolean auto = DEFAULTS.autoDraw();
        try {
            if (w.hasKey("autoDraw") && !w.isNull("autoDraw")) auto = w.getBoolean("autoDraw");
        } catch (RuntimeException e) {
            // the default
        }
        return new Settings(mode == null ? Mode.POINTS : mode, entries == null ? Entries.PER_TILE : entries, min, winners, auto);
    }

    /** The rules as they are saved in {@code config.win}. */
    public static DataObject toJson(Settings s) {
        return DataObject.empty().put("mode", s.mode().name()).put("entries", s.entries().name()).put("minTiles", s.minTiles()).put("winners", s.winners()).put("autoDraw", s.autoDraw());
    }

    /** A copy of {@code config} with the rules set; rules equal to the defaults are taken out, so the event reads exactly as one that never had them. */
    public static DataObject withSettings(DataObject config, Settings s) {
        DataObject copy = DataObject.fromJson(config.toString());
        if (s.isDefault()) copy.remove("win");
        else copy.put("win", toJson(s));
        return copy;
    }

    static Mode mode(String raw) {
        if (raw == null) return null;
        try {
            return Mode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Entries entries(String raw) {
        if (raw == null) return null;
        try {
            return Entries.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long clamp(DataObject o, String key, long fallback, long min, long max) {
        try {
            if (!o.hasKey(key) || o.isNull(key)) return fallback;
            return Math.max(min, Math.min(max, number(o, key)));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long number(DataObject o, String key) {
        Object v = o.get(key);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v).strip());
    }

    // ---------- the rules in plain words ----------

    /** Each rule as one sentence, for the event manager and the player's event page. */
    public static List<String> describe(Settings s) {
        List<String> lines = new ArrayList<>();
        switch (s.mode()) {
            case POINTS -> lines.add("Most points wins: the standings decide the places.");
            case DRAW -> {
                lines.add("The bot draws " + (s.winners() == 1 ? "a winner" : s.winners() + " winners") + " at random among everyone who qualified.");
                lines.add("To qualify, finish at least " + tiles(s.minTiles()) + ".");
                lines.add(s.entries() == Entries.PER_TILE ? "Every finished tile is one entry, so more tiles means better odds." : "Everyone who qualifies gets one entry.");
                lines.add(s.autoDraw() ? "The draw happens by itself when the event ends, and the seed is shown so anyone can check it." : "Staff start the draw; the seed is shown so anyone can check it.");
            }
            case FIRST_LINE -> lines.add("The first to finish a line wins; places go in the order lines were finished.");
            case FIRST_BLACKOUT -> lines.add("The first to finish every tile wins; places go in the order boards were finished.");
        }
        return lines;
    }

    static String tiles(int n) {
        return n == 1 ? "1 tile" : n + " tiles";
    }

    // ---------- who qualifies for the draw ----------

    /**
     * Everyone who qualifies for the draw, in the fixed order the draw uses (subject keys sorted). Counts the tiles each team or player has finished at
     * {@code until} (null: everything logged), walking the log in time order like the standings, so an entry taken back undoes its tile. {@code allowed}
     * leaves out subjects no longer in the event (removed players, deleted teams).
     */
    public static List<Entrant> entrants(Settings s, List<ObjectiveSpec> objectives, List<Entry> log, Instant until, Predicate<String> allowed) {
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        Map<String, Map<String, Long>> totals = new HashMap<>();
        for (Entry e : log.stream().sorted(Comparator.comparing(Entry::at)).toList()) {
            if (until != null && e.at().isAfter(until)) continue;
            if (!byKey.containsKey(e.objectiveKey())) continue;
            Map<String, Long> mine = totals.computeIfAbsent(e.subject(), k -> new HashMap<>());
            mine.put(e.objectiveKey(), Math.max(0, mine.getOrDefault(e.objectiveKey(), 0L) + e.amount()));
        }
        List<Entrant> out = new ArrayList<>();
        for (var subject : totals.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!allowed.test(subject.getKey())) continue;
            int tiles = 0;
            for (var t : subject.getValue().entrySet()) {
                if (EventScoring.completions(byKey.get(t.getKey()), t.getValue()) >= 1) tiles++;
            }
            if (tiles < s.minTiles()) continue;
            out.add(new Entrant(subject.getKey(), tiles, s.entries() == Entries.PER_TILE ? tiles : 1));
        }
        return out;
    }

    // ---------- the draw ----------

    /**
     * Picks up to {@code count} different winners from {@code entrants} with a {@link Random} seeded by {@code seed}, never anyone in {@code exclude}.
     * Ticket i belongs to the entrant whose entries cover it, in list order. Each pick is the giveaway's fair pick over the tickets still in play, and a
     * winner's other tickets leave the pool, so nobody wins twice. The same seed and entrants always give the same winners.
     */
    public static List<String> draw(List<Entrant> entrants, int count, Set<String> exclude, long seed) {
        Random random = new Random(seed);
        Set<String> out = new HashSet<>(exclude);
        List<String> winners = new ArrayList<>();
        while (winners.size() < count) {
            List<Long> tickets = new ArrayList<>();
            List<String> owner = new ArrayList<>();
            for (Entrant e : entrants) {
                if (out.contains(e.subject())) continue;
                for (int i = 0; i < e.tickets(); i++) {
                    tickets.add((long) owner.size());
                    owner.add(e.subject());
                }
            }
            List<Long> pick = GiveawayDraw.pick(tickets, 1, Set.of(), random);
            if (pick.isEmpty()) break;
            String winner = owner.get(pick.get(0).intValue());
            winners.add(winner);
            out.add(winner);
        }
        return winners;
    }

    /**
     * The winners now, place 1 first: the latest full draw, with each later reroll putting its new winner in the place it filled (a reroll that found
     * nobody left leaves the place empty, shown as null).
     */
    public static List<String> winners(List<Round> rounds) {
        List<String> places = new ArrayList<>();
        for (Round r : rounds.stream().sorted(Comparator.comparingInt(Round::round)).toList()) {
            if (!r.reroll()) {
                places = new ArrayList<>(r.picked());
            } else if (r.place() >= 1 && r.place() <= places.size()) {
                places.set(r.place() - 1, r.picked().isEmpty() ? null : r.picked().get(0));
            }
        }
        return places;
    }

    /** Everyone already drawn since the latest full draw (winners and those rerolled away), who can't be drawn again. */
    public static Set<String> alreadyDrawn(List<Round> rounds) {
        Set<String> seen = new HashSet<>();
        for (Round r : rounds.stream().sorted(Comparator.comparingInt(Round::round)).toList()) {
            if (!r.reroll()) seen.clear();
            seen.addAll(r.picked());
            if (r.replaced() != null) seen.add(r.replaced());
        }
        return seen;
    }

    /** The latest full draw, or null before the first one. */
    public static Round latestDraw(List<Round> rounds) {
        return rounds.stream().filter(r -> !r.reroll()).max(Comparator.comparingInt(Round::round)).orElse(null);
    }

    /** Whether staff may draw (again): before any draw, or when the latest full draw found nobody who qualified. */
    public static boolean mayDraw(List<Round> rounds) {
        Round latest = latestDraw(rounds);
        return latest == null || latest.picked().isEmpty();
    }

    /** Whether the bot should draw by itself now: a draw event set to draw at its end, past its end time but not long past, not drawn yet. */
    public static boolean autoDue(Settings s, Instant endsAt, List<Round> rounds, Instant now) {
        if (!s.draws() || !s.autoDraw() || endsAt == null || !rounds.isEmpty()) return false;
        return !now.isBefore(endsAt) && now.isBefore(endsAt.plus(AUTO_WINDOW));
    }

    /** "1st", "2nd", "3rd", "4th", ... */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /** The announcement of a full draw. {@code nameOf} gives a team's or player's name (never a mention). */
    public static String drawText(String eventName, Settings s, Round r, Function<String, String> nameOf) {
        StringBuilder text = new StringBuilder("### " + eventName + " · Prize draw\n");
        if (r.picked().isEmpty()) {
            text.append("Nobody qualified for the draw (it needs at least ").append(tiles(s.minTiles())).append(" finished), so there is no winner yet.");
        } else if (r.picked().size() == 1) {
            text.append("The winner is **").append(nameOf.apply(r.picked().get(0))).append("**!");
        } else {
            text.append("The winners are:");
            for (int i = 0; i < r.picked().size(); i++) text.append("\n").append(ordinal(i + 1)).append(". **").append(nameOf.apply(r.picked().get(i))).append("**");
        }
        text.append("\n-# ").append(r.tickets()).append(r.tickets() == 1 ? " entry" : " entries").append(" from ").append(r.entrants().size())
                .append(r.entrants().size() == 1 ? " qualifier" : " qualifiers").append(" (").append(s.entries() == Entries.PER_TILE ? "one per finished tile" : "one each")
                .append("). Seed `").append(Long.toUnsignedString(r.seed())).append("`: the entry list and seed are on the event page, so anyone can check the draw.");
        return clip(text.toString());
    }

    /** The announcement of a reroll for a winner who didn't claim. */
    public static String rerollText(String eventName, Round r, Function<String, String> nameOf) {
        StringBuilder text = new StringBuilder("### " + eventName + " · Prize draw, redrawn\n");
        String old = r.replaced() == null ? "The winner" : "**" + nameOf.apply(r.replaced()) + "**";
        text.append(old).append(" didn't claim ").append(ordinal(r.place())).append(" place, so the bot drew again: ");
        if (r.picked().isEmpty()) text.append("nobody else qualified, so the place stays empty.");
        else text.append("**").append(nameOf.apply(r.picked().get(0))).append("** wins!");
        text.append("\n-# Seed `").append(Long.toUnsignedString(r.seed())).append("`, from the same entries as the draw.");
        return clip(text.toString());
    }

    static String clip(String text) {
        return text.length() <= MAX_POST ? text : text.substring(0, MAX_POST - 1) + "…";
    }

    // ---------- first to a line or the board ----------

    /**
     * Teams or players in the order they first finished a line ({@link Mode#FIRST_LINE}: any bonus group but the whole board) or every tile
     * ({@link Mode#FIRST_BLACKOUT}), first first. Walks the log in time order; a subject whose line or board is undone by an entry taken back loses its
     * time and only counts again from when it finishes again. Subjects who never finished are left out. Empty for the other modes.
     */
    public static List<String> finishOrder(Mode mode, List<ObjectiveSpec> objectives, List<EventBonuses.Bonus> bonuses, List<Entry> log) {
        if (mode != Mode.FIRST_LINE && mode != Mode.FIRST_BLACKOUT) return List.of();
        Map<String, ObjectiveSpec> byKey = new LinkedHashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        Set<String> all = byKey.keySet();
        if (all.isEmpty()) return List.of();
        List<EventBonuses.Bonus> lines = bonuses.stream().filter(b -> !BoardUpdates.isBlackout(b, all)).toList();
        Map<String, Map<String, Long>> totals = new HashMap<>();
        Map<String, Instant> reachedAt = new HashMap<>();
        Map<String, Integer> reachedSeq = new HashMap<>();
        int seq = 0;
        for (Entry e : log.stream().sorted(Comparator.comparing(Entry::at)).toList()) {
            ObjectiveSpec o = byKey.get(e.objectiveKey());
            if (o == null) continue;
            Map<String, Long> mine = totals.computeIfAbsent(e.subject(), k -> new HashMap<>());
            mine.put(o.key(), Math.max(0, mine.getOrDefault(o.key(), 0L) + e.amount()));
            boolean done = mode == Mode.FIRST_BLACKOUT ? BoardUpdates.done(all, mine, byKey) : anyDone(lines, mine, byKey);
            if (done && !reachedAt.containsKey(e.subject())) {
                reachedAt.put(e.subject(), e.at());
                reachedSeq.put(e.subject(), seq++);
            } else if (!done) {
                reachedAt.remove(e.subject());
                reachedSeq.remove(e.subject());
            }
        }
        return reachedAt.keySet().stream().sorted(Comparator.comparing((String k) -> reachedAt.get(k)).thenComparing(reachedSeq::get)).toList();
    }

    private static boolean anyDone(Collection<EventBonuses.Bonus> lines, Map<String, Long> totals, Map<String, ObjectiveSpec> byKey) {
        for (EventBonuses.Bonus line : lines) {
            if (BoardUpdates.done(line.keys(), totals, byKey)) return true;
        }
        return false;
    }
}
