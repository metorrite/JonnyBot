package com.younglings.bot.clansite;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanSiteRulesTest {

    @Test
    void aThemeKeepsOnlyPaletteColoursKnownFontsAndHttpsImages() {
        List<String> problems = new ArrayList<>();
        String theme = ClanSiteRules.cleanTheme(DataObject.fromJson("""
                {"primary":"crimson","accent":"slate","font":"serif","logoUrl":"https://cdn.example.test/l.png","extra":"<script>"}"""), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        DataObject t = DataObject.fromJson(theme);
        assertEquals("crimson", t.getString("primary"));
        assertEquals("serif", t.getString("font"));
        assertFalse(t.hasKey("extra"), "unknown keys are dropped");

        List<String> bad = new ArrayList<>();
        ClanSiteRules.cleanTheme(DataObject.fromJson("""
                {"primary":"#ff00ff","font":"comic","bannerUrl":"javascript:alert(1)","logoUrl":"http://example.test/a.png"}"""), bad);
        assertEquals(4, bad.size(), bad.toString());
    }

    @Test
    void pagesAreKnownOnceEachWithShortTitles() {
        List<String> problems = new ArrayList<>();
        String pages = ClanSiteRules.cleanPages(DataArray.fromJson("""
                [{"key":"home","enabled":false},{"key":"roster","showJoined":true},{"key":"roster"},{"key":"custom1","title":"  Our   rules "}]"""), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        DataArray p = DataArray.fromJson(pages);
        assertEquals(3, p.length(), "a repeated page is kept once");
        assertTrue(p.getObject(0).getBoolean("enabled"), "home is always on");
        assertTrue(p.getObject(1).getBoolean("showJoined"));
        assertEquals("Our rules", p.getObject(2).getString("title"));

        List<String> bad = new ArrayList<>();
        ClanSiteRules.cleanPages(DataArray.fromJson("""
                [{"key":"admin"},{"key":"custom2","enabled":true},{"key":"about","title":"%s"}]""".formatted("x".repeat(40))), bad);
        assertEquals(3, bad.size(), bad.toString());
    }

    @Test
    void blocksAreCheckedByKind() {
        List<String> problems = new ArrayList<>();
        List<ClanSiteBlock> blocks = ClanSiteRules.cleanBlocks(7L, "home", DataArray.fromJson("""
                [{"kind":"TEXT","body":{"markdown":"## Hi"}},
                 {"kind":"IMAGE","body":{"url":"https://cdn.example.test/b.png","alt":"Banner"}},
                 {"kind":"LINKS","body":{"links":[{"label":"Discord","url":"https://discord.com"}]}}]"""), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(3, blocks.size());
        assertEquals(2, blocks.get(2).position());

        List<String> bad = new ArrayList<>();
        ClanSiteRules.cleanBlocks(7L, "home", DataArray.fromJson("""
                [{"kind":"HTML","body":{}},{"kind":"TEXT","body":{"markdown":""}},{"kind":"IMAGE","body":{"url":"data:image/png;base64,AA"}},
                 {"kind":"LINKS","body":{"links":[{"label":"x","url":"javascript:alert(1)"}]}}]"""), bad);
        assertEquals(4, bad.size(), bad.toString());

        List<String> wrongPage = new ArrayList<>();
        ClanSiteRules.cleanBlocks(7L, "roster", DataArray.empty(), wrongPage);
        assertEquals(1, wrongPage.size(), "data pages have no blocks");
    }
}
