package com.younglings.bot.clansite;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shape of a website builder v2 layout (T-301; the website's packages/builder has the same model and checks each widget's settings,
 * docs/BUILDER-V2.md section 2). The bot checks only the shape and the sizes: the version, pages, sections, columns adding up to 12, widget
 * ids and types, and how much each part may hold. What a widget's settings mean is the website's business; the bot stores them as given,
 * small and well-formed. {@link #clean} returns the layout to store, rebuilt from the known fields only, or adds what is wrong to {@code problems}.
 */
public final class ClanSiteLayoutRules {
    private ClanSiteLayoutRules() {}

    public static final int VERSION = 1;
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_PAGES = 12;
    public static final int MAX_SECTIONS = 20;
    public static final int MAX_COLUMNS = 4;
    public static final int MAX_WIDGETS_PER_COLUMN = 12;
    public static final int MAX_SETTINGS_BYTES = 4096;
    public static final int MAX_TITLE = 40;
    public static final int GRID = 12;

    public static final Set<String> THEMES = Set.of("modern", "parchment", "minimal");
    public static final Set<String> MODES = Set.of("light", "dark");
    public static final Set<String> TONES = Set.of("plain", "panel", "accent");
    public static final Set<String> UNMET = Set.of("hide", "placeholder");

    private static final Pattern ID = Pattern.compile("[a-z0-9]{6,16}");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern STEP = Pattern.compile("[a-z-]{1,20}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern UPLOAD = Pattern.compile("/api/uploads/[0-9a-f]{32}(\\?v=\\d{1,10})?");
    public static final int MAX_URL = 500;
    /** Paths a page can't have: the site's own files and the routes that are never a clan's. */
    static final Set<String> RESERVED_PATHS = Set.of("api", "_next", "brand", "site", "p");

    /** The layout to store, or null with the reasons in {@code problems}. */
    public static String clean(DataObject layout, List<String> problems) {
        int before = problems.size();
        if (layout == null) {
            problems.add("Send the layout.");
            return null;
        }
        if (bytes(layout) > MAX_BYTES) {
            problems.add("The site is too big to save (" + (MAX_BYTES / 1024) + " KB at most). Remove some widgets or text.");
            return null;
        }
        if (layout.getInt("v", -1) != VERSION) problems.add("This layout is from another version of the builder. Reload the page.");

        DataObject out = DataObject.empty().put("v", VERSION);
        out.put("look", look(layout.optObject("look").orElse(null), problems));

        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        DataArray pages = layout.optArray("pages").orElse(null);
        DataArray cleanPages = DataArray.empty();
        if (pages == null || pages.isEmpty()) problems.add("A site needs at least its Home page.");
        else if (pages.length() > MAX_PAGES) problems.add("A site can have up to " + MAX_PAGES + " pages.");
        else {
            for (int i = 0; i < pages.length(); i++) {
                if (!pages.isType(i, DataType.OBJECT)) {
                    problems.add("Page " + (i + 1) + " isn't a page.");
                    continue;
                }
                cleanPages.add(page(pages.getObject(i), i == 0, ids, paths, problems));
            }
        }
        out.put("pages", cleanPages);

        DataObject wizard = layout.optObject("wizard").orElse(null);
        if (wizard != null) {
            String step = wizard.getString("step", "");
            DataObject progress = DataObject.empty().put("step", STEP.matcher(step).matches() ? step : "start").put("done", wizard.getBoolean("done", false));
            String slug = wizard.getString("slug", "");
            if (SLUG.matcher(slug).matches() && slug.length() <= 32) progress.put("slug", slug);
            String name = wizard.getString("name", "");
            if (!name.isBlank()) progress.put("name", name.length() > 60 ? name.substring(0, 60) : name);
            String tagline = wizard.getString("tagline", "");
            if (!tagline.isBlank()) progress.put("tagline", tagline.length() > 120 ? tagline.substring(0, 120) : tagline);
            out.put("wizard", progress);
        }
        return problems.size() == before ? out.toString() : null;
    }

    private static DataObject look(DataObject look, List<String> problems) {
        DataObject t = look == null ? DataObject.empty() : look;
        String theme = t.getString("theme", "modern");
        String mode = t.getString("mode", "dark");
        if (!THEMES.contains(theme)) problems.add("Pick one of the three templates.");
        if (!MODES.contains(mode)) problems.add("Pick light or dark.");
        DataObject out = DataObject.empty().put("theme", theme).put("mode", mode);
        if (t.isNull("accent") || !t.hasKey("accent")) out.putNull("accent");
        else {
            String accent = t.getString("accent", "");
            if (!HEX.matcher(accent).matches()) problems.add("The clan colour must be a #rrggbb colour.");
            out.put("accent", accent);
        }
        for (String key : List.of("logo", "banner")) {
            String url = t.isNull(key) ? "" : t.getString(key, "").strip();
            if (url.isEmpty()) out.putNull(key);
            else if (!picture(url)) problems.add("The " + key + " must be an https picture address or an upload.");
            else out.put(key, url);
        }
        return out;
    }

    /** A picture address: https (no user-info), or the website's own upload path; at most {@link #MAX_URL} characters. */
    static boolean picture(String url) {
        if (url.length() > MAX_URL) return false;
        if (UPLOAD.matcher(url).matches()) return true;
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static DataObject page(DataObject page, boolean home, Set<String> ids, Set<String> paths, List<String> problems) {
        String id = id(page, ids, "page", problems);
        String title = page.getString("title", "").strip();
        String label = title.isEmpty() ? "A page" : "The page \"" + title + "\"";
        if (title.isEmpty() || title.length() > MAX_TITLE) problems.add("Give every page a title of 1 to " + MAX_TITLE + " characters.");
        String path = page.getString("path", "").strip();
        if (home) {
            if (!path.isEmpty()) problems.add("The first page is the home page, at the site's own address.");
        } else if (!PATH.matcher(path).matches() || path.length() > 32 || RESERVED_PATHS.contains(path)) {
            problems.add(label + " needs an address of lower-case letters, numbers and single dashes (up to 32).");
        } else if (!paths.add(path)) {
            problems.add("Two pages have the address /" + path + ".");
        }
        boolean hidden = !home && page.getBoolean("hidden", false);

        DataArray sections = page.optArray("sections").orElse(DataArray.empty());
        DataArray cleanSections = DataArray.empty();
        if (sections.length() > MAX_SECTIONS) problems.add(label + " has more than " + MAX_SECTIONS + " sections.");
        else {
            for (int i = 0; i < sections.length(); i++) {
                if (!sections.isType(i, DataType.OBJECT)) {
                    problems.add(label + " has a section that isn't one.");
                    continue;
                }
                cleanSections.add(section(sections.getObject(i), label, ids, problems));
            }
        }
        return DataObject.empty().put("id", id).put("title", title).put("path", home ? "" : path).put("hidden", hidden).put("sections", cleanSections);
    }

    private static DataObject section(DataObject section, String label, Set<String> ids, List<String> problems) {
        String id = id(section, ids, "section", problems);
        String tone = section.getString("tone", "plain");
        if (!TONES.contains(tone)) problems.add(label + " has a section with an unknown background.");
        DataArray columns = section.optArray("columns").orElse(DataArray.empty());
        DataArray cleanColumns = DataArray.empty();
        if (columns.isEmpty() || columns.length() > MAX_COLUMNS) {
            problems.add(label + " has a section without 1 to " + MAX_COLUMNS + " columns.");
            return DataObject.empty().put("id", id).put("tone", tone).put("columns", cleanColumns);
        }
        int total = 0;
        for (int i = 0; i < columns.length(); i++) {
            if (!columns.isType(i, DataType.OBJECT)) {
                problems.add(label + " has a column that isn't one.");
                continue;
            }
            DataObject column = columns.getObject(i);
            int span = column.getInt("span", 0);
            if (span < 1 || span > GRID) problems.add(label + " has a column of an impossible width.");
            total += span;
            cleanColumns.add(column(column, span, label, ids, problems));
        }
        if (total != GRID) problems.add(label + " has a section whose columns don't fill the width.");
        return DataObject.empty().put("id", id).put("tone", tone).put("columns", cleanColumns);
    }

    private static DataObject column(DataObject column, int span, String label, Set<String> ids, List<String> problems) {
        String id = id(column, ids, "column", problems);
        DataArray widgets = column.optArray("widgets").orElse(DataArray.empty());
        DataArray cleanWidgets = DataArray.empty();
        if (widgets.length() > MAX_WIDGETS_PER_COLUMN) problems.add(label + " has a column with more than " + MAX_WIDGETS_PER_COLUMN + " widgets.");
        else {
            for (int i = 0; i < widgets.length(); i++) {
                if (!widgets.isType(i, DataType.OBJECT)) {
                    problems.add(label + " has a widget that isn't one.");
                    continue;
                }
                cleanWidgets.add(widget(widgets.getObject(i), label, ids, problems));
            }
        }
        return DataObject.empty().put("id", id).put("span", span).put("widgets", cleanWidgets);
    }

    private static DataObject widget(DataObject widget, String label, Set<String> ids, List<String> problems) {
        String id = id(widget, ids, "widget", problems);
        String type = widget.getString("type", "");
        if (!TYPE.matcher(type).matches()) problems.add(label + " has a widget of an unknown kind.");
        DataObject settings = widget.isType("settings", DataType.OBJECT) ? widget.getObject("settings") : DataObject.empty();
        if (bytes(settings) > MAX_SETTINGS_BYTES) problems.add(label + " has a widget with too much in it (" + (MAX_SETTINGS_BYTES / 1024) + " KB at most).");
        String unmet = widget.getString("unmet", "hide");
        return DataObject.empty().put("id", id).put("type", type).put("settings", settings).put("unmet", UNMET.contains(unmet) ? unmet : "hide");
    }

    private static String id(DataObject part, Set<String> ids, String what, List<String> problems) {
        String id = part.getString("id", "");
        if (!ID.matcher(id).matches()) problems.add("A " + what + " has a broken id. Reload the page and try again.");
        else if (!ids.add(id)) problems.add("Two parts of the site share the id " + id + ". Reload the page and try again.");
        return id;
    }

    static int bytes(DataObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
