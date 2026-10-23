package com.younglings.bot.clansite;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The builder v2 layout's shape checks (T-301). */
class ClanSiteLayoutRulesTest {
    private static int counter = 100000;

    private static String nextId() {
        return "id" + (counter++);
    }

    private static DataObject widget(String type) {
        return DataObject.empty().put("id", nextId()).put("type", type).put("settings", DataObject.empty().put("title", "Hello")).put("unmet", "hide");
    }

    private static DataObject column(int span, DataObject... widgets) {
        DataArray list = DataArray.empty();
        for (DataObject w : widgets) list.add(w);
        return DataObject.empty().put("id", nextId()).put("span", span).put("widgets", list);
    }

    private static DataObject section(DataObject... columns) {
        DataArray list = DataArray.empty();
        for (DataObject c : columns) list.add(c);
        return DataObject.empty().put("id", nextId()).put("tone", "plain").put("columns", list);
    }

    private static DataObject page(String title, String path, DataObject... sections) {
        DataArray list = DataArray.empty();
        for (DataObject s : sections) list.add(s);
        return DataObject.empty().put("id", nextId()).put("title", title).put("path", path).put("hidden", false).put("sections", list);
    }

    private static DataObject layout(DataObject... pages) {
        DataArray list = DataArray.empty();
        for (DataObject p : pages) list.add(p);
        return DataObject.empty().put("v", 1).put("look", DataObject.empty().put("theme", "parchment").put("mode", "light").put("accent", "#c9a227"))
                .put("pages", list);
    }

    private static DataObject good() {
        return layout(page("Home", "", section(column(8, widget("text")), column(4, widget("events"), widget("roster")))),
                page("Roster", "roster", section(column(12, widget("roster")))));
    }

    private static List<String> problems(DataObject layout) {
        List<String> problems = new ArrayList<>();
        ClanSiteLayoutRules.clean(layout, problems);
        return problems;
    }

    @Test
    void aWellFormedLayoutIsKeptAsSent() {
        List<String> problems = new ArrayList<>();
        String clean = ClanSiteLayoutRules.clean(good(), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertNotNull(clean);
        DataObject back = DataObject.fromJson(clean);
        assertEquals("parchment", back.getObject("look").getString("theme"));
        assertEquals(2, back.getArray("pages").length());
        assertEquals("text", back.getArray("pages").getObject(0).getArray("sections").getObject(0).getArray("columns").getObject(0)
                .getArray("widgets").getObject(0).getString("type"));
    }

    @Test
    void unknownFieldsAreDropped() {
        DataObject layout = good().put("script", "alert(1)");
        layout.getArray("pages").getObject(0).put("html", "<b>x</b>");
        String clean = ClanSiteLayoutRules.clean(layout, new ArrayList<>());
        assertNotNull(clean);
        assertFalse(clean.contains("alert"));
        assertFalse(clean.contains("<b>"));
    }

    @Test
    void theVersionMustMatch() {
        assertFalse(problems(good().put("v", 2)).isEmpty());
    }

    @Test
    void columnsMustFillTheWidth() {
        DataObject bad = layout(page("Home", "", section(column(6, widget("text")), column(4))));
        assertTrue(problems(bad).stream().anyMatch(p -> p.contains("fill the width")));
        DataObject five = layout(page("Home", "", section(column(3), column(3), column(2), column(2), column(2))));
        assertFalse(problems(five).isEmpty(), "five columns");
    }

    @Test
    void theHomePageIsFirstAndPathsAreUniqueAndSafe() {
        assertFalse(problems(layout(page("Home", "about"))).isEmpty(), "home needs the root path");
        assertFalse(problems(layout(page("Home", ""), page("A", "about"), page("B", "about"))).isEmpty(), "duplicate path");
        assertFalse(problems(layout(page("Home", ""), page("A", "api"))).isEmpty(), "reserved path");
        assertFalse(problems(layout(page("Home", ""), page("A", "../x"))).isEmpty(), "odd path");
        assertFalse(problems(layout(page("", ""))).isEmpty(), "a title is needed");
    }

    @Test
    void idsAreCheckedAndUnique() {
        DataObject layout = good();
        DataObject first = layout.getArray("pages").getObject(0);
        layout.getArray("pages").getObject(1).put("id", first.getString("id"));
        assertTrue(problems(layout).stream().anyMatch(p -> p.contains("share the id")));
        DataObject broken = good();
        broken.getArray("pages").getObject(0).put("id", "BAD ID");
        assertFalse(problems(broken).isEmpty());
    }

    @Test
    void sizesAreLimited() {
        DataObject[] pages = new DataObject[ClanSiteLayoutRules.MAX_PAGES + 1];
        pages[0] = page("Home", "");
        for (int i = 1; i < pages.length; i++) pages[i] = page("P" + i, "p" + i + "x");
        assertFalse(problems(layout(pages)).isEmpty(), "too many pages");

        DataObject big = widget("text");
        big.put("settings", DataObject.empty().put("markdown", "x".repeat(ClanSiteLayoutRules.MAX_SETTINGS_BYTES + 1)));
        assertFalse(problems(layout(page("Home", "", section(column(12, big))))).isEmpty(), "settings too big");

        DataObject huge = good().put("pad", "x".repeat(ClanSiteLayoutRules.MAX_BYTES));
        assertFalse(problems(huge).isEmpty(), "whole layout too big");
    }

    @Test
    void lookAndWidgetFieldsAreChecked() {
        DataObject badTheme = good();
        badTheme.getObject("look").put("theme", "neon");
        assertFalse(problems(badTheme).isEmpty());
        DataObject badAccent = good();
        badAccent.getObject("look").put("accent", "red");
        assertFalse(problems(badAccent).isEmpty());
        DataObject noAccent = good();
        noAccent.getObject("look").putNull("accent");
        assertTrue(problems(noAccent).isEmpty());
        assertFalse(problems(layout(page("Home", "", section(column(12, widget("Bad Type")))))).isEmpty());
    }

    @Test
    void wizardProgressIsKept() {
        DataObject layout = good().put("wizard", DataObject.empty().put("step", "pages").put("done", false).put("slug", "green-angels")
                .put("name", "The Green Angels").put("slugBad", "x"));
        DataObject wizard = DataObject.fromJson(ClanSiteLayoutRules.clean(layout, new ArrayList<>())).getObject("wizard");
        assertEquals("pages", wizard.getString("step"));
        assertEquals("green-angels", wizard.getString("slug"));
        assertEquals("The Green Angels", wizard.getString("name"));
        assertFalse(wizard.hasKey("slugBad"));

        DataObject odd = good().put("wizard", DataObject.empty().put("step", "pages").put("slug", "Not A Slug"));
        assertFalse(DataObject.fromJson(ClanSiteLayoutRules.clean(odd, new ArrayList<>())).getObject("wizard").hasKey("slug"));
    }

    @Test
    void logoAndBannerAreHttpsOrAnUpload() {
        assertTrue(ClanSiteLayoutRules.picture("https://cdn.example.test/logo.png"));
        assertTrue(ClanSiteLayoutRules.picture("/api/uploads/" + "a".repeat(32) + "?v=3"));
        assertFalse(ClanSiteLayoutRules.picture("http://cdn.example.test/logo.png"));
        assertFalse(ClanSiteLayoutRules.picture("javascript:alert(1)"));
        assertFalse(ClanSiteLayoutRules.picture("https://user:pw@cdn.example.test/x.png"));
        assertFalse(ClanSiteLayoutRules.picture("/api/uploads/../../secret"));

        DataObject layout = good();
        layout.getObject("look").put("logo", "https://cdn.example.test/logo.png").put("banner", "data:image/png;base64,AAAA");
        assertTrue(problems(layout).stream().anyMatch(p -> p.contains("banner")));
    }

    @Test
    void nothingIsNotALayout() {
        List<String> problems = new ArrayList<>();
        assertNull(ClanSiteLayoutRules.clean(null, problems));
        assertFalse(problems.isEmpty());
    }
}
