package com.runeclans.bot.lookup;

import com.runeclans.bot.lookup.WikiInfoService.Kind;
import com.runeclans.bot.lookup.WikiInfoService.Status;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.runeclans.bot.lookup.GePriceServiceTest.sample;
import static org.junit.jupiter.api.Assertions.*;

/** Parsing against pages saved from the real wiki (src/test/resources/lookup); nothing here touches the network. */
class WikiInfoServiceTest {
    private static WikiInfoService service(AtomicInteger calls, boolean down) {
        return new WikiInfoService(LookupHttp.withFetcher(url -> {
            calls.incrementAndGet();
            if (down) return Optional.empty();
            if (url.contains("Category:Bosses")) return Optional.of(sample("wiki-cat-bosses.json"));
            if (url.contains("Category:Quests")) return Optional.of(sample("wiki-cat-quests.json"));
            if (url.contains("prop=extracts") && url.contains("Araxxor")) return Optional.of(sample("wiki-extract-araxxor.json"));
            if (url.contains("prop=extracts") && url.contains("Nomad")) return Optional.of(sample("wiki-extract-nomad.json"));
            if (url.contains("rvsection") && url.contains("Araxxor")) return Optional.of(sample("wiki-section0-araxxor.json"));
            if (url.contains("rvsection") && url.contains("Nomad")) return Optional.of(sample("wiki-section0-nomad.json"));
            return Optional.empty();
        }));
    }

    @Test
    void categoryListsDropTheListPages() {
        var bosses = WikiInfoService.parseCategory(sample("wiki-cat-bosses.json"));
        assertTrue(bosses.contains("Araxxor"));
        assertFalse(bosses.contains("Bosses"));
        var quests = WikiInfoService.parseCategory(sample("wiki-cat-quests.json"));
        assertTrue(quests.contains("Nomad's Requiem"));
        assertTrue(quests.stream().noneMatch(q -> q.startsWith("List of")));
    }

    @Test
    void aBossHasFactsAndAStrategyLink() {
        var o = service(new AtomicInteger(), false).lookup(Kind.BOSS, "araxxor");
        assertEquals(Status.FOUND, o.status());
        var s = o.summary();
        assertEquals("Araxxor", s.title());
        assertTrue(s.extract().startsWith("Araxxor is"));
        assertEquals("2500", s.facts().get("Combat level"));
        assertEquals("Yes", s.facts().get("Members"));
        // the wiki's own HasStrategy template names the page
        assertEquals("https://runescape.wiki/w/Araxxi/Strategies", s.guideUrl());
        assertNotNull(s.thumbnail());
    }

    @Test
    void aQuestHasFactsAndAQuickGuideLink() {
        var s = service(new AtomicInteger(), false).lookup(Kind.QUEST, "Nomad's Requiem").summary();
        assertEquals("Grandmaster", s.facts().get("Difficulty"));
        assertEquals("Edgeville", s.facts().get("Area"));
        assertEquals("https://runescape.wiki/w/Nomad's_Requiem/Quick_guide", s.guideUrl());
    }

    @Test
    void onlyNamesInTheCategoryCanBeLookedUp() {
        assertEquals(Status.NOT_FOUND, service(new AtomicInteger(), false).lookup(Kind.BOSS, "Main Page").status());
        assertEquals(Status.NOT_FOUND, service(new AtomicInteger(), false).lookup(Kind.BOSS, "").status());
    }

    @Test
    void wikiDownIsUnavailable() {
        assertEquals(Status.UNAVAILABLE, service(new AtomicInteger(), true).lookup(Kind.QUEST, "Nomad's Requiem").status());
        assertTrue(service(new AtomicInteger(), true).names(Kind.BOSS).isEmpty());
    }

    @Test
    void garbageIsHandled() {
        assertTrue(WikiInfoService.parseExtract("{}").isEmpty());
        assertEquals("", WikiInfoService.parseSection0("nope"));
        assertTrue(WikiInfoService.infobox("no template", "Infobox Quest").isEmpty());
    }
}
