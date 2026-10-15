package com.younglings.bot.wheel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names on a wheel. A name appears once per entry, so someone with two entries has two slices; the slices are spread evenly round the wheel rather
 * than sitting side by side, so a double entry is two chances in different places. Extra entries are typed one per line: {@code Name}, or
 * {@code Name x3} for three entries (an extra entry given as a prize or to make up for a missed one). Pure, so it can be tested.
 */
public final class WheelEntries {
    public static final int MAX_ENTRIES = 300;
    public static final int MAX_NAME = 40;
    public static final int MAX_PER_LINE = 20;
    public static final int MAX_LINES = 100;
    private static final Pattern LINE = Pattern.compile("^(.*?)(?:\\s*[xX×]\\s*(\\d{1,3}))?$");

    /** The names typed in (one per entry), and what was wrong with any line that couldn't be used. */
    public record Parsed(List<String> names, List<String> problems) {}

    private WheelEntries() {}

    /** Reads typed extras. Blank lines are skipped; a bullet or number at the start of a line is ignored so a pasted list works. */
    public static Parsed parseExtras(String text) {
        List<String> names = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (text == null || text.isBlank()) return new Parsed(names, problems);
        String[] lines = text.split("\\R");
        int used = 0;
        for (String raw : lines) {
            String line = raw.strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s+", "");
            if (line.isEmpty()) continue;
            if (++used > MAX_LINES) {
                problems.add("Only the first " + MAX_LINES + " lines were used.");
                break;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1).strip();
            int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            if (name.isEmpty()) {
                problems.add("\"" + line + "\" has no name.");
                continue;
            }
            if (name.length() > MAX_NAME) {
                problems.add("\"" + name.substring(0, 20) + "...\" is longer than " + MAX_NAME + " characters.");
                continue;
            }
            if (count < 1 || count > MAX_PER_LINE) {
                problems.add("\"" + name + "\" can have between 1 and " + MAX_PER_LINE + " entries.");
                continue;
            }
            for (int i = 0; i < count; i++) names.add(name);
        }
        return new Parsed(names, problems);
    }

    /** One entry per name for each time it appears, grouped case-insensitively under the first spelling seen, in the order slices are drawn. */
    public static List<String> spread(List<String> entries) {
        Map<String, String> display = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String entry : entries) {
            String key = entry.strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            display.putIfAbsent(key, entry.strip());
            counts.merge(key, 1, Integer::sum);
        }
        record Slot(double position, String name) {}
        List<Slot> slots = new ArrayList<>();
        for (var e : counts.entrySet()) {
            int count = e.getValue();
            for (int j = 0; j < count; j++) slots.add(new Slot((j + 0.5) / count, display.get(e.getKey())));
        }
        // names with one entry sit where their order puts them; a name with several is spread over the whole wheel
        List<String> order = new ArrayList<>(display.values());
        slots.sort(Comparator.comparingDouble(Slot::position).thenComparingInt(s -> order.indexOf(s.name())));
        return slots.stream().map(Slot::name).toList();
    }

    /** The distinct names in the order first seen, for a list to copy. */
    public static List<String> distinct(List<String> entries) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String entry : entries) {
            String name = entry.strip();
            if (!name.isEmpty()) seen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return new ArrayList<>(seen.values());
    }
}
