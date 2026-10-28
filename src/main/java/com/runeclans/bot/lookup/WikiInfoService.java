package com.runeclans.bot.lookup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Short summaries of RuneScape 3 bosses and quests from the RuneScape Wiki: its opening paragraph, a few facts from the page's infobox (difficulty and area for
 * a quest, combat level and weakness for a boss) and a link to the page's guide. Only pages the wiki files under its Bosses or Quests category can be asked
 * for, which is also where the autocomplete names come from. Everything goes through {@link LookupHttp}, so it is cached and polite.
 */
@BService
public class WikiInfoService {
    static final String API = "https://runescape.wiki/api.php";

    public enum Kind {
        BOSS("Bosses", "Infobox Monster", "HasStrategy", "Strategies", new String[][]{{"level", "Combat level"}, {"lifepoints", "Life points"}, {"weakness", "Weakness"}, {"members", "Members"}, {"leagueRegion", "Region"}}),
        QUEST("Quests", "Infobox Quest", "Has quick guide", "Quick guide", new String[][]{{"difficulty", "Difficulty"}, {"members", "Members"}, {"area", "Area"}, {"combat", "Combat"}, {"release", "Released"}});

        final String category;
        final String infobox;
        final String guideTemplate;
        final String guideSubpage;
        final String[][] facts;

        Kind(String category, String infobox, String guideTemplate, String guideSubpage, String[][] facts) {
            this.category = category;
            this.infobox = infobox;
            this.guideTemplate = guideTemplate;
            this.guideSubpage = guideSubpage;
            this.facts = facts;
        }
    }

    /** {@code facts} is label to value, in display order; {@code guideUrl} may be null. */
    public record Summary(String title, String url, String extract, String thumbnail, Map<String, String> facts, String guideLabel, String guideUrl) {}

    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public record Outcome(Status status, Summary summary) {}

    private final LookupHttp http;

    public WikiInfoService(LookupHttp http) {
        this.http = http;
    }

    /** Every name the wiki files under this kind's category (empty if the wiki can't be reached). */
    public List<String> names(Kind kind) {
        return http.get(API + "?action=query&list=categorymembers&cmtitle=Category:" + kind.category + "&cmlimit=500&cmnamespace=0&format=json")
                .map(WikiInfoService::parseCategory).orElse(List.of());
    }

    public Outcome lookup(Kind kind, String typed) {
        String wanted = typed == null ? "" : typed.strip();
        if (wanted.isEmpty() || wanted.length() > 100) return new Outcome(Status.NOT_FOUND, null);
        List<String> names = names(kind);
        if (names.isEmpty()) return new Outcome(Status.UNAVAILABLE, null);
        String title = names.stream().filter(n -> n.equalsIgnoreCase(wanted)).findFirst().orElse(null);
        if (title == null) return new Outcome(Status.NOT_FOUND, null);

        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
        Optional<String> extract = http.get(API + "?action=query&prop=extracts%7Cpageimages%7Cinfo&exintro=1&explaintext=1&exchars=700&redirects=1&piprop=thumbnail&pithumbsize=200&inprop=url&format=json&formatversion=2&titles=" + encoded);
        if (extract.isEmpty()) return new Outcome(Status.UNAVAILABLE, null);
        Optional<Summary> base = parseExtract(extract.get());
        if (base.isEmpty()) return new Outcome(Status.NOT_FOUND, null);

        // The facts are a bonus: if the page source can't be had, the summary is still shown.
        Optional<String> source = http.get(API + "?action=query&prop=revisions&rvprop=content&rvslots=main&rvsection=0&redirects=1&format=json&formatversion=2&titles=" + encoded);
        Summary s = base.get();
        if (source.isPresent()) s = withFacts(kind, s, parseSection0(source.get()));
        return new Outcome(Status.FOUND, s);
    }

    // ---------- parsing ----------

    static List<String> parseCategory(String json) {
        try {
            DataArray members = DataObject.fromJson(json).getObject("query").getArray("categorymembers");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < members.length(); i++) {
                String title = members.getObject(i).getString("title", "");
                // category pages also hold the lists and hubs about the topic, which are not entries
                if (title.isBlank() || title.startsWith("List of") || title.equals("Bosses") || title.equals("Quests") || title.contains(":")) continue;
                out.add(title);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static Optional<Summary> parseExtract(String json) {
        try {
            DataObject page = DataObject.fromJson(json).getObject("query").getArray("pages").getObject(0);
            if (page.getBoolean("missing", false) || !page.hasKey("extract")) return Optional.empty();
            String extract = page.getString("extract").strip();
            if (extract.isEmpty()) return Optional.empty();
            String thumb = page.hasKey("thumbnail") ? page.getObject("thumbnail").getString("source", null) : null;
            return Optional.of(new Summary(page.getString("title"), page.getString("fullurl", ""), extract, thumb, Map.of(), null, null));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The wikitext of a page's opening section, or "" when it can't be read. */
    static String parseSection0(String json) {
        try {
            return DataObject.fromJson(json).getObject("query").getArray("pages").getObject(0).getArray("revisions").getObject(0)
                    .getObject("slots").getObject("main").getString("content", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Summary withFacts(Kind kind, Summary s, String wikitext) {
        Map<String, String> infobox = infobox(wikitext, kind.infobox);
        Map<String, String> facts = new LinkedHashMap<>();
        for (String[] f : kind.facts) {
            String value = infobox.get(f[0]);
            if (value != null && !value.isBlank()) facts.put(f[1], tidy(f[0], value));
        }
        String guide = guidePage(kind, s.title(), wikitext);
        String guideUrl = guide == null ? null : "https://runescape.wiki/w/" + URLEncoder.encode(guide.replace(' ', '_'), StandardCharsets.UTF_8).replace("%2F", "/").replace("%27", "'");
        return new Summary(s.title(), s.url(), s.extract(), s.thumbnail(), facts, guideUrl == null ? null : kind.guideSubpage, guideUrl);
    }

    private static String tidy(String key, String value) {
        if (key.equals("members")) return value.equalsIgnoreCase("yes") ? "Yes" : value.equalsIgnoreCase("no") ? "Free-to-play" : value;
        return value;
    }

    /** The "|name = value" lines of the infobox template, with wiki markup removed. */
    static Map<String, String> infobox(String wikitext, String template) {
        Map<String, String> out = new LinkedHashMap<>();
        int start = wikitext.toLowerCase(Locale.ROOT).indexOf("{{" + template.toLowerCase(Locale.ROOT));
        if (start < 0) return out;
        int end = wikitext.indexOf("\n}}", start);
        String block = wikitext.substring(start, end < 0 ? wikitext.length() : end);
        Matcher m = Pattern.compile("(?m)^\\|\\s*([A-Za-z_0-9]+)\\s*=\\s*(.*)$").matcher(block);
        while (m.find()) out.putIfAbsent(m.group(1), DndTable.clean(m.group(2)));
        return out;
    }

    /** The page the wiki points to for strategy or a quick guide: named by its template, otherwise the usual subpage if the page says it has one. */
    static String guidePage(Kind kind, String title, String wikitext) {
        Matcher m = Pattern.compile("\\{\\{\\s*" + Pattern.quote(kind.guideTemplate) + "\\s*\\|\\s*page1\\s*=\\s*([^|}]+)", Pattern.CASE_INSENSITIVE).matcher(wikitext);
        if (m.find()) return m.group(1).strip();
        if (Pattern.compile("\\{\\{\\s*" + Pattern.quote(kind.guideTemplate) + "\\s*\\}\\}", Pattern.CASE_INSENSITIVE).matcher(wikitext).find()) return title + "/" + kind.guideSubpage;
        return null;
    }
}
