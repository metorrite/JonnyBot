package com.runeclans.bot.clansite;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a clan may save for its website, checked before anything is stored (the website's lib/clanSiteTheme.ts has the same lists for its
 * editor; keep them in step). A clan picks colours from a checked palette, one of three fonts, https images, which pages show (and their
 * order, and the titles of up to three custom pages), and content blocks: text in a small Markdown subset, an image, or links. No HTML,
 * CSS or code anywhere: the website renders all of it as text.
 * <p>Each {@code clean...} method returns the value to store, or adds what is wrong to {@code problems}.
 */
public final class ClanSiteRules {
    private ClanSiteRules() {}

    public static final Set<String> PALETTE = Set.of("gold", "crimson", "emerald", "azure", "violet", "teal", "orange", "slate");
    public static final Set<String> FONTS = Set.of("sans", "serif", "mono");
    public static final List<String> PAGES = List.of("home", "roster", "stats", "events", "recaps", "apply", "about", "custom1", "custom2", "custom3");
    /** Pages that can carry blocks (the data pages fill themselves). */
    public static final Set<String> BLOCK_PAGES = Set.of("home", "apply", "about", "custom1", "custom2", "custom3");

    public static final int MAX_TITLE = 30;
    public static final int MAX_URL = 500;
    public static final int MAX_BLOCKS_PER_PAGE = 20;
    public static final int MAX_MARKDOWN = 5000;
    public static final int MAX_LINKS = 12;
    public static final int MAX_LABEL = 60;
    public static final int MAX_CAPTION = 200;

    /** An https address (images), or null when blank; a bad one is a problem. */
    static String httpsUrl(Object raw, String what, List<String> problems) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        if (s.length() > MAX_URL || !isWebUrl(s, true)) {
            problems.add(what + " must be an https:// address of at most " + MAX_URL + " characters.");
            return null;
        }
        return s;
    }

    static boolean isWebUrl(String s, boolean httpsOnly) {
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean ok = httpsOnly ? scheme.equals("https") : (scheme.equals("https") || scheme.equals("http"));
            return ok && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The theme to store: only known keys, palette colours, a known font and https images. */
    public static String cleanTheme(DataObject theme, List<String> problems) {
        DataObject out = DataObject.empty();
        if (theme == null) return out.toString();
        for (String key : List.of("primary", "accent")) {
            String value = theme.getString(key, null);
            if (value == null || value.isBlank()) continue;
            if (!PALETTE.contains(value)) problems.add("Pick the " + key + " colour from the list.");
            else out.put(key, value);
        }
        String font = theme.getString("font", null);
        if (font != null && !font.isBlank()) {
            if (!FONTS.contains(font)) problems.add("Pick a font from the list.");
            else out.put("font", font);
        }
        String logo = httpsUrl(theme.opt("logoUrl").orElse(null), "The logo", problems);
        if (logo != null) out.put("logoUrl", logo);
        String banner = httpsUrl(theme.opt("bannerUrl").orElse(null), "The banner", problems);
        if (banner != null) out.put("bannerUrl", banner);
        return out.toString();
    }

    /** The page list to store: known pages once each, short titles, on/off, and the roster's join-date switch. */
    public static String cleanPages(DataArray pages, List<String> problems) {
        DataArray out = DataArray.empty();
        if (pages == null) return out.toString();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < pages.length(); i++) {
            DataObject page = pages.getObject(i);
            String key = page.getString("key", "");
            if (!PAGES.contains(key)) {
                problems.add("There is no page called \"" + key + "\".");
                continue;
            }
            if (!seen.add(key)) continue;
            DataObject clean = DataObject.empty().put("key", key).put("enabled", key.equals("home") || page.getBoolean("enabled", true));
            String title = page.getString("title", "").replaceAll("\\s+", " ").trim();
            if (title.length() > MAX_TITLE) problems.add("A page title can be at most " + MAX_TITLE + " characters.");
            else if (!title.isEmpty()) clean.put("title", title);
            if (key.startsWith("custom") && title.isEmpty() && page.getBoolean("enabled", true)) problems.add("Give each of your own pages a title (or switch it off).");
            if (key.equals("roster")) clean.put("showJoined", page.getBoolean("showJoined", false));
            out.add(clean);
        }
        return out.toString();
    }

    /** One page's blocks to store, in order. */
    public static List<ClanSiteBlock> cleanBlocks(long guildId, String page, DataArray blocks, List<String> problems) {
        List<ClanSiteBlock> out = new ArrayList<>();
        if (!BLOCK_PAGES.contains(page)) {
            problems.add("That page can't have blocks.");
            return out;
        }
        if (blocks == null) return out;
        if (blocks.length() > MAX_BLOCKS_PER_PAGE) {
            problems.add("A page can have at most " + MAX_BLOCKS_PER_PAGE + " blocks.");
            return out;
        }
        for (int i = 0; i < blocks.length(); i++) {
            DataObject block = blocks.getObject(i);
            ClanSiteBlock.Kind kind = ClanSiteBlock.Kind.parse(block.getString("kind", "")).orElse(null);
            DataObject body = block.optObject("body").orElse(DataObject.empty());
            if (kind == null) {
                problems.add("Block " + (i + 1) + " is not a kind this page knows.");
                continue;
            }
            DataObject clean = switch (kind) {
                case TEXT -> {
                    String md = body.getString("markdown", "");
                    if (md.isBlank()) problems.add("Text block " + (i + 1) + " is empty.");
                    if (md.length() > MAX_MARKDOWN) problems.add("Text block " + (i + 1) + " is longer than " + MAX_MARKDOWN + " characters.");
                    yield DataObject.empty().put("markdown", md);
                }
                case IMAGE -> {
                    String raw = body.getString("url", "").trim();
                    String url = raw.isEmpty() ? null : httpsUrl(raw, "Image " + (i + 1), problems);
                    if (raw.isEmpty()) problems.add("Image " + (i + 1) + " needs an https:// address.");
                    yield DataObject.empty().put("url", url == null ? "" : url).put("alt", cap(body.getString("alt", ""), MAX_CAPTION))
                            .put("caption", cap(body.getString("caption", ""), MAX_CAPTION));
                }
                case LINKS -> {
                    DataArray links = body.optArray("links").orElse(DataArray.empty());
                    DataArray cleanLinks = DataArray.empty();
                    if (links.length() == 0) problems.add("Links block " + (i + 1) + " has no links.");
                    if (links.length() > MAX_LINKS) problems.add("Links block " + (i + 1) + " can have at most " + MAX_LINKS + " links.");
                    for (int j = 0; j < Math.min(links.length(), MAX_LINKS); j++) {
                        DataObject link = links.getObject(j);
                        String label = link.getString("label", "").trim();
                        String url = link.getString("url", "").trim();
                        if (label.isEmpty() || label.length() > MAX_LABEL) problems.add("Each link needs a name of at most " + MAX_LABEL + " characters.");
                        if (url.length() > MAX_URL || !isWebUrl(url, false)) problems.add("Each link needs a web address starting with https:// or http://.");
                        cleanLinks.add(DataObject.empty().put("label", label).put("url", url));
                    }
                    yield DataObject.empty().put("links", cleanLinks);
                }
            };
            out.add(new ClanSiteBlock(guildId, page, i, kind, clean.toString()));
        }
        return out;
    }

    private static String cap(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
