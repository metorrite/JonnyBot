package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Entry;
import com.younglings.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A bingo's board update rules (T-650), from {@code config.updates}: when the bot redraws the board after tiles are approved, whether it posts a recap of
 * the day at the daily reset, and which milestones it announces. Every part is off or "as today" unless an organiser turns it on, so an event that never
 * saved these behaves exactly as before: the board is redrawn a few seconds after each approval and nothing else is posted. Pure, so it can be tested.
 * <p>
 * The daily reset is RuneScape's: 00:00 UTC.
 */
public final class BoardUpdates {
    /** When the board is redrawn after progress changes. */
    public enum Mode {
        /** A few seconds after each approval, a burst making one update (today's behaviour, the default). */
        LIVE,
        /** At most once every {@code everyMinutes} minutes. */
        BATCHED,
        /** Once a day, at the daily reset. */
        DAILY
    }

    /** The milestones the bot can announce. Each has its own switch. */
    public enum Kind {
        /** The first team or player to finish any line (a row, column or diagonal; a tier, row or ring of a shape). */
        FIRST_LINE,
        /** The first team or player to finish every tile. */
        FIRST_BLACKOUT,
        /** The first time anyone finishes a tile: one post per tile. */
        FIRST_TILE
    }

    public static final Mode DEFAULT_MODE = Mode.LIVE;
    public static final int DEFAULT_EVERY_MINUTES = 30;
    public static final int MIN_EVERY_MINUTES = 5;
    public static final int MAX_EVERY_MINUTES = 12 * 60;
    /** A milestone reached longer ago than this is never announced (so turning a switch on mid-event doesn't post a pile of old news). */
    public static final Duration MILESTONE_FRESH = Duration.ofHours(6);
    /** A recap is only posted this long after the reset (a bot that was down at reset posts it late, but not hours later). */
    public static final Duration RECAP_WINDOW = Duration.ofHours(2);
    /** How many finished tiles a recap lists before "and N more". */
    public static final int RECAP_TILES = 15;
    static final int MAX_POST = 1900;

    /** {@code channelId} null = the event's announcements channel, else its live board channel. */
    public record Settings(Mode mode, int everyMinutes, boolean recap, boolean firstLine, boolean firstBlackout, boolean firstTile, Long channelId) {
        public boolean milestones() {
            return firstLine || firstBlackout || firstTile;
        }

        public boolean on(Kind kind) {
            return switch (kind) {
                case FIRST_LINE -> firstLine;
                case FIRST_BLACKOUT -> firstBlackout;
                case FIRST_TILE -> firstTile;
            };
        }

        /** Whether this is exactly how an event that never saved update rules behaves. */
        public boolean isDefault() {
            return equals(DEFAULTS);
        }
    }

    public static final Settings DEFAULTS = new Settings(DEFAULT_MODE, DEFAULT_EVERY_MINUTES, false, false, false, false, null);

    /** One milestone: who reached it ({@code subject} as in the standings, "u:id" or "t:id"), what ({@code detail}: the tile or line), and when. */
    public record Milestone(String key, Kind kind, String subject, String detail, Instant at) {}

    /** One tile a team or player finished, and when. */
    public record Finished(String objectiveKey, String label, String subject, Instant at) {}

    private BoardUpdates() {}

    // ---------- settings ----------

    /** The rules of an event; anything missing or unreadable is the default, so a broken setting never stops the board. */
    public static Settings of(DataObject config) {
        try {
            if (config == null || !config.hasKey("updates") || config.isNull("updates")) return DEFAULTS;
            return read(config.getObject("updates"), true);
        } catch (RuntimeException e) {
            return DEFAULTS;
        }
    }

    /** What is wrong with {@code updates} as an organiser sent it; empty when it can be saved. */
    public static List<String> problems(DataObject updates) {
        List<String> problems = new ArrayList<>();
        if (updates == null) return List.of("Send the update rules.");
        try {
            if (updates.hasKey("mode") && !updates.isNull("mode") && mode(updates.getString("mode")) == null) {
                problems.add("Choose when the board updates: after every approval, every few minutes, or once a day at reset.");
            }
            if (updates.hasKey("everyMinutes") && !updates.isNull("everyMinutes")) {
                long minutes = number(updates, "everyMinutes");
                if (minutes < MIN_EVERY_MINUTES || minutes > MAX_EVERY_MINUTES) {
                    problems.add("The board can update every " + MIN_EVERY_MINUTES + " to " + MAX_EVERY_MINUTES + " minutes.");
                }
            }
            if (updates.hasKey("channelId") && !updates.isNull("channelId") && !updates.getString("channelId", "").isBlank()
                    && channel(updates.getString("channelId")) == null) {
                problems.add("Choose a channel for the posts (the channel id looks wrong).");
            }
            for (String key : new String[] {"recap", "firstLine", "firstBlackout", "firstTile"}) {
                if (updates.hasKey(key) && !updates.isNull(key) && !(updates.get(key) instanceof Boolean)) problems.add("Each switch is on or off.");
            }
        } catch (RuntimeException e) {
            problems.add("The update rules couldn't be read.");
        }
        return problems.stream().distinct().toList();
    }

    /** Reads rules an organiser sent (check {@link #problems} first). */
    public static Settings read(DataObject sent) {
        return read(sent, false);
    }

    /** Reads saved or sent rules. {@code lenient}: an unreadable part is its default instead of a problem. */
    static Settings read(DataObject u, boolean lenient) {
        Mode mode = u.hasKey("mode") && !u.isNull("mode") ? mode(u.getString("mode")) : DEFAULT_MODE;
        if (mode == null) mode = DEFAULT_MODE;
        int every = DEFAULT_EVERY_MINUTES;
        if (u.hasKey("everyMinutes") && !u.isNull("everyMinutes")) {
            try {
                every = (int) Math.max(MIN_EVERY_MINUTES, Math.min(MAX_EVERY_MINUTES, number(u, "everyMinutes")));
            } catch (RuntimeException e) {
                if (!lenient) throw e;
            }
        }
        Long channel = null;
        if (u.hasKey("channelId") && !u.isNull("channelId")) {
            channel = channel(u.getString("channelId", ""));
        }
        return new Settings(mode, every, flag(u, "recap"), flag(u, "firstLine"), flag(u, "firstBlackout"), flag(u, "firstTile"), channel);
    }

    /** The rules as they are saved in {@code config.updates}. */
    public static DataObject toJson(Settings s) {
        return DataObject.empty().put("mode", s.mode().name()).put("everyMinutes", s.everyMinutes()).put("recap", s.recap()).put("firstLine", s.firstLine())
                .put("firstBlackout", s.firstBlackout()).put("firstTile", s.firstTile()).put("channelId", s.channelId() == null ? null : String.valueOf(s.channelId()));
    }

    /** A copy of {@code config} with the rules set; the rules equal to the defaults are taken out, so the event reads exactly as one that never had them. */
    public static DataObject withSettings(DataObject config, Settings s) {
        DataObject copy = DataObject.fromJson(config.toString());
        if (s.isDefault()) copy.remove("updates");
        else copy.put("updates", toJson(s));
        return copy;
    }

    /** A Discord channel id (a snowflake of 15 to 19 digits that fits a long), or null when {@code raw} isn't one. */
    static Long channel(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (!s.matches("\\d{15,19}")) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null; // 19 digits above Long.MAX_VALUE
        }
    }

    static Mode mode(String raw) {
        if (raw == null) return null;
        try {
            return Mode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean flag(DataObject o, String key) {
        try {
            return o.hasKey(key) && !o.isNull(key) && o.getBoolean(key, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long number(DataObject o, String key) {
        Object v = o.get(key);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v).strip());
    }

    // ---------- when the board is redrawn ----------

    /** The most recent daily reset at or before {@code now} (00:00 UTC). */
    public static Instant latestReset(Instant now) {
        return now.truncatedTo(ChronoUnit.DAYS);
    }

    /** The next daily reset after {@code now}. */
    public static Instant nextReset(Instant now) {
        return latestReset(now).plus(Duration.ofDays(1));
    }

    /**
     * Whether a board held back since {@code heldSince} (the first approval not yet shown) should be redrawn now. {@code lastDrawn} is when the bot last
     * redrew it because of approvals (null: not since it started). LIVE boards are never held.
     */
    public static boolean due(Settings s, Instant heldSince, Instant lastDrawn, Instant now) {
        return switch (s.mode()) {
            case LIVE -> true;
            case BATCHED -> lastDrawn == null || !now.isBefore(lastDrawn.plus(Duration.ofMinutes(s.everyMinutes())));
            case DAILY -> heldSince.isBefore(latestReset(now));
        };
    }

    /** When a board held back since {@code heldSince} will be redrawn, for the dashboard. */
    public static Instant dueAt(Settings s, Instant heldSince, Instant lastDrawn) {
        return switch (s.mode()) {
            case LIVE -> heldSince;
            case BATCHED -> lastDrawn == null ? heldSince : lastDrawn.plus(Duration.ofMinutes(s.everyMinutes()));
            case DAILY -> nextReset(heldSince);
        };
    }

    // ---------- the rules in plain words ----------

    /** Each rule as one sentence, for the dashboard's Updates section and the player's event page. */
    public static List<String> describe(Settings s) {
        return describe(s, false);
    }

    /** As {@link #describe(Settings)}; {@code standingsHidden} adds what the event's hidden standings keep out of the posts. */
    public static List<String> describe(Settings s, boolean standingsHidden) {
        List<String> lines = new ArrayList<>();
        lines.add(switch (s.mode()) {
            case LIVE -> "The board updates a few seconds after each approved tile.";
            case BATCHED -> "The board updates at most once every " + minutesText(s.everyMinutes()) + ", with every tile approved since.";
            case DAILY -> "The board updates once a day, at the daily reset (00:00 UTC).";
        });
        if (s.recap()) lines.add("Every day at reset the bot posts a recap: the tiles finished the day before and the top 3.");
        if (s.firstLine()) lines.add("The bot announces the first team or player to finish a line.");
        if (s.firstBlackout()) lines.add("The bot announces the first team or player to finish the whole board.");
        if (s.firstTile()) lines.add("The bot announces the first time anyone finishes each tile.");
        if (standingsHidden && s.recap()) lines.add("While the standings are hidden, the recap lists the tiles without saying who finished them, and leaves out the top 3.");
        if (standingsHidden && s.milestones()) lines.add("While the standings are hidden, no milestones are announced.");
        return lines;
    }

    static String minutesText(int minutes) {
        if (minutes % 60 == 0) return minutes == 60 ? "hour" : (minutes / 60) + " hours";
        return minutes + " minutes";
    }

    // ---------- milestones ----------

    /**
     * Every milestone reached so far, in the order they happened: the first to finish each tile, the first to finish a line (any bonus but the blackout) and
     * the first to finish every objective. Walks the progress log in time order like the standings do, so an entry taken back undoes its tile.
     */
    public static List<Milestone> milestones(List<ObjectiveSpec> objectives, List<EventBonuses.Bonus> bonuses, List<Entry> log) {
        Map<String, ObjectiveSpec> byKey = new LinkedHashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        Set<String> all = byKey.keySet();
        List<EventBonuses.Bonus> lines = bonuses.stream().filter(b -> !isBlackout(b, all)).toList();

        Map<String, Map<String, Long>> totals = new HashMap<>();
        List<Milestone> out = new ArrayList<>();
        boolean lineSeen = false, blackoutSeen = false;
        Set<String> tilesSeen = new java.util.HashSet<>();
        for (Entry e : log.stream().sorted(Comparator.comparing(Entry::at)).toList()) {
            ObjectiveSpec o = byKey.get(e.objectiveKey());
            if (o == null) continue;
            Map<String, Long> mine = totals.computeIfAbsent(e.subject(), k -> new HashMap<>());
            long before = mine.getOrDefault(o.key(), 0L);
            long after = Math.max(0, before + e.amount());
            mine.put(o.key(), after);
            if (EventScoring.completions(o, after) < 1 || EventScoring.completions(o, before) >= 1) continue;
            // this entry finished the tile for this subject
            if (tilesSeen.add(o.key())) out.add(new Milestone("tile:" + o.key(), Kind.FIRST_TILE, e.subject(), o.label(), e.at()));
            if (!lineSeen) {
                for (EventBonuses.Bonus line : lines) {
                    if (line.keys().contains(o.key()) && done(line.keys(), mine, byKey)) {
                        lineSeen = true;
                        out.add(new Milestone("line", Kind.FIRST_LINE, e.subject(), line.name(), e.at()));
                        break;
                    }
                }
            }
            if (!blackoutSeen && !all.isEmpty() && done(all, mine, byKey)) {
                blackoutSeen = true;
                out.add(new Milestone("blackout", Kind.FIRST_BLACKOUT, e.subject(), "", e.at()));
            }
        }
        return out;
    }

    private static boolean isBlackout(EventBonuses.Bonus b, Set<String> all) {
        return b.name().equalsIgnoreCase("Blackout") || (!all.isEmpty() && Set.copyOf(b.keys()).containsAll(all));
    }

    private static boolean done(java.util.Collection<String> keys, Map<String, Long> totals, Map<String, ObjectiveSpec> byKey) {
        for (String key : keys) {
            ObjectiveSpec o = byKey.get(key);
            if (o == null || EventScoring.completions(o, totals.getOrDefault(key, 0L)) < 1) return false;
        }
        return true;
    }

    /** The milestones to announce now: switched on, and reached recently enough to still be news. */
    public static List<Milestone> toAnnounce(Settings s, List<Milestone> all, Instant now) {
        Instant fresh = now.minus(MILESTONE_FRESH);
        return all.stream().filter(m -> s.on(m.kind()) && !m.at().isBefore(fresh)).toList();
    }

    /** The post for a milestone. {@code who} is the team's or player's name (never a mention). */
    public static String milestoneText(String eventName, Milestone m, String who) {
        String text = switch (m.kind()) {
            case FIRST_LINE -> "### " + eventName + " · First line!\n**" + who + "** is the first to finish a line" + (m.detail().isBlank() ? "" : " (" + m.detail() + ")") + ".";
            case FIRST_BLACKOUT -> "### " + eventName + " · Blackout!\n**" + who + "** is the first to finish every tile on the board.";
            case FIRST_TILE -> "### " + eventName + " · New tile done\n**" + who + "** is the first to finish **" + m.detail() + "**.";
        };
        return clip(text);
    }

    // ---------- the daily recap ----------

    /** The tiles finished between {@code from} (inclusive) and {@code to} (exclusive), oldest first. */
    public static List<Finished> finishedBetween(List<ObjectiveSpec> objectives, List<Entry> log, Instant from, Instant to) {
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        Map<String, Map<String, Long>> totals = new HashMap<>();
        List<Finished> out = new ArrayList<>();
        for (Entry e : log.stream().sorted(Comparator.comparing(Entry::at)).toList()) {
            ObjectiveSpec o = byKey.get(e.objectiveKey());
            if (o == null) continue;
            Map<String, Long> mine = totals.computeIfAbsent(e.subject(), k -> new HashMap<>());
            long before = mine.getOrDefault(o.key(), 0L);
            long after = Math.max(0, before + e.amount());
            mine.put(o.key(), after);
            if (EventScoring.completions(o, after) > EventScoring.completions(o, before) && !e.at().isBefore(from) && e.at().isBefore(to)) {
                out.add(new Finished(o.key(), o.label(), e.subject(), e.at()));
            }
        }
        return out;
    }

    /**
     * The recap of the day that ended at {@code reset}: the tiles finished that day and the top 3. {@code tableShown} false (the event hides its standings)
     * keeps the ranks out and says why instead, and lists the tiles without who finished them. Null when nothing was finished that day: a quiet day posts nothing.
     */
    public static String recapText(String eventName, Instant reset, List<Finished> finished, List<Standing> standings, Function<String, String> nameOf,
                                   boolean tableShown, String hiddenText) {
        if (finished.isEmpty()) return null;
        LocalDate day = reset.minus(Duration.ofDays(1)).atZone(ZoneOffset.UTC).toLocalDate();
        StringBuilder text = new StringBuilder("### " + eventName + " · Daily recap, " + day.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) + "\n");
        text.append("**").append(finished.size()).append(finished.size() == 1 ? " tile" : " tiles").append(" finished**");
        int shown = 0;
        for (Finished f : finished) {
            if (shown == RECAP_TILES) break;
            text.append("\n- ").append(f.label());
            if (tableShown) text.append(" · ").append(nameOf.apply(f.subject()));
            shown++;
        }
        if (finished.size() > shown) text.append("\n- and ").append(finished.size() - shown).append(" more");
        text.append("\n**Top 3**");
        if (!tableShown) {
            text.append("\n").append(hiddenText);
        } else if (standings.isEmpty()) {
            text.append("\nNo points yet.");
        } else {
            for (int i = 0; i < Math.min(3, standings.size()); i++) {
                Standing s = standings.get(i);
                text.append("\n").append(i + 1).append(". **").append(nameOf.apply(s.subject())).append("** · ").append(s.points()).append(" pts");
            }
        }
        return clip(text.toString());
    }

    /** The key a recap is remembered by, so the day is only ever posted once: {@code recap:2026-10-26}. */
    public static String recapKey(Instant reset) {
        return "recap:" + reset.minus(Duration.ofDays(1)).atZone(ZoneOffset.UTC).toLocalDate();
    }

    static String clip(String text) {
        return text.length() <= MAX_POST ? text : text.substring(0, MAX_POST - 1) + "…";
    }

    // ---------- previews for the dashboard ----------

    /** What each post looks like, with made-up names when the event has nothing yet, so an organiser sees it before switching it on. */
    public static Map<String, String> previews(String eventName, Settings s, List<ObjectiveSpec> objectives, Instant now) {
        return previews(eventName, s, objectives, now, null);
    }

    /** As {@link #previews(String, Settings, List, Instant)}; a {@code hiddenText} (the event hides its standings) shows the recap as it would really be posted. */
    public static Map<String, String> previews(String eventName, Settings s, List<ObjectiveSpec> objectives, Instant now, String hiddenText) {
        String tile = objectives.isEmpty() ? "Nex: Torva boots" : objectives.get(0).label();
        String other = objectives.size() < 2 ? "Araxxor: Noxious blade" : objectives.get(1).label();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("board", "### " + eventName + " · Running\n" + switch (s.mode()) {
            case LIVE -> "The board picture is edited a few seconds after each approval.";
            case BATCHED -> "The board picture is edited at most once every " + minutesText(s.everyMinutes()) + ".";
            case DAILY -> "The board picture is edited once a day at reset.";
        });
        Instant reset = latestReset(now);
        List<Finished> sample = List.of(new Finished("a", tile, "t:1", reset.minus(Duration.ofHours(5))), new Finished("b", other, "t:2", reset.minus(Duration.ofHours(2))));
        List<Standing> top = List.of(new Standing("t:1", 120, 6, Map.of(), null), new Standing("t:2", 95, 5, Map.of(), null), new Standing("t:3", 40, 2, Map.of(), null));
        Map<String, String> names = Map.of("t:1", "Team Zaros", "t:2", "Team Seren", "t:3", "Team Guthix");
        out.put("recap", recapText(eventName, reset, sample, top, k -> names.getOrDefault(k, "A team"), hiddenText == null, hiddenText == null ? "" : hiddenText));
        out.put("firstLine", milestoneText(eventName, new Milestone("line", Kind.FIRST_LINE, "t:1", "Row 2", now), "Team Zaros"));
        out.put("firstBlackout", milestoneText(eventName, new Milestone("blackout", Kind.FIRST_BLACKOUT, "t:1", "", now), "Team Zaros"));
        out.put("firstTile", milestoneText(eventName, new Milestone("tile:a", Kind.FIRST_TILE, "t:2", tile, now), "Team Seren"));
        return out;
    }
}
