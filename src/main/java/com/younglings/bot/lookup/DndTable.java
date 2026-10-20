package com.younglings.bot.lookup;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the "Frequency" table of the wiki's Distractions and Diversions page (its wikitext, via the wiki API) into rows. The wiki is the source of truth
 * for what recurs and how often, so nothing about the game's schedule is hard-coded here; a layout the parser doesn't recognise gives no rows rather
 * than wrong ones.
 */
public final class DndTable {
    private DndTable() {}

    static final String URL = "https://runescape.wiki/api.php?action=parse&page=Distractions_and_Diversions&prop=wikitext&section=3&format=json";

    /** {@code category} is the wiki's grouping: When available, Daily, Weekly, Monthly or Item. {@code timing} and {@code frequency} are as the wiki words them (empty if "n/a"). */
    public record Entry(String category, String name, String timing, String frequency, String requirements) {}

    private static final Pattern PIPED_LINK = Pattern.compile("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]");
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]]*)\\]\\]");
    private static final Pattern REF = Pattern.compile("<ref[^>]*/>|<ref[^>]*>.*?</ref>", Pattern.DOTALL);
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{[^}]*\\}\\}");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    public static List<Entry> parse(String apiJson) {
        try {
            String wikitext = DataObject.fromJson(apiJson).getObject("parse").getObject("wikitext").getString("*");
            return parseWikitext(wikitext);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static List<Entry> parseWikitext(String wikitext) {
        List<Entry> out = new ArrayList<>();
        String category = "";
        for (String row : wikitext.split("\\n\\|-")) {
            List<String> cells = new ArrayList<>();
            for (String line : row.split("\\n")) {
                if (!line.startsWith("|") || line.startsWith("|}") || line.startsWith("|+") || line.startsWith("|-")) continue;
                String cell = line.substring(1);
                int bar = cell.indexOf('|');
                // "attributes | content": the part before a lone bar is attributes, unless it is the start of a link or template.
                String head = cell.strip();
                if (bar > 0 && cell.substring(0, bar).contains("=") && !head.startsWith("[[") && !head.startsWith("{{")) cell = cell.substring(bar + 1);
                cells.add(clean(cell));
            }
            if (cells.size() == 5) {
                category = cells.get(0);
                cells = cells.subList(1, 5);
            } else if (cells.size() != 4 || category.isEmpty()) {
                continue;
            }
            if (cells.get(0).isEmpty()) continue;
            out.add(new Entry(category, cells.get(0), cells.get(1), cells.get(2), cells.get(3)));
        }
        return out;
    }

    static String clean(String markup) {
        String s = REF.matcher(markup).replaceAll("");
        s = s.replace("{{NA}}", "").replace("{{na}}", "");
        s = TEMPLATE.matcher(s).replaceAll("");
        s = s.replaceAll("(?i)<br\\s*/?>", "; ");
        s = PIPED_LINK.matcher(s).replaceAll("$2");
        s = LINK.matcher(s).replaceAll("$1");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("'''", "").replace("''", "");
        return s.replaceAll("\\s+", " ").strip();
    }
}
