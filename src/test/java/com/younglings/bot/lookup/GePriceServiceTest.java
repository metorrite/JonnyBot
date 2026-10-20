package com.younglings.bot.lookup;

import com.younglings.bot.lookup.GePriceService.Outcome;
import com.younglings.bot.lookup.GePriceService.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing is checked against responses saved from the real services (src/test/resources/lookup); no test calls the network. */
class GePriceServiceTest {
    static String sample(String name) {
        try (var in = GePriceServiceTest.class.getResourceAsStream("/lookup/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GePriceService service(AtomicInteger calls, boolean down) {
        return new GePriceService(LookupHttp.withFetcher(url -> {
            calls.incrementAndGet();
            if (down) return Optional.empty();
            if (url.contains("latest?name=Nonexistent")) return Optional.of(sample("ge-unknown.json"));
            if (url.contains("latest?")) return Optional.of(sample("ge-latest.json"));
            if (url.contains("last90d?")) return Optional.of(sample("ge-last90d.json"));
            if (url.contains("opensearch")) return Optional.of(sample("wiki-opensearch.json"));
            return Optional.empty();
        }));
    }

    @Test
    void parsesLatestPrice() {
        var p = GePriceService.parseLatest(sample("ge-latest.json")).orElseThrow();
        assertEquals("Dragon bones", p.name());
        assertEquals("536", p.id());
        assertTrue(p.price() > 0);
        assertNotNull(p.at());
        assertEquals("https://runescape.wiki/w/Dragon_bones", p.wikiUrl());
    }

    @Test
    void unknownItemIsNotFound() {
        assertTrue(GePriceService.parseLatest(sample("ge-unknown.json")).isEmpty());
        assertEquals(Status.NOT_FOUND, service(new AtomicInteger(), false).lookup("Nonexistent thing zz").status());
    }

    @Test
    void garbageIsNotFoundNotACrash() {
        assertTrue(GePriceService.parseLatest("<html>oops</html>").isEmpty());
        assertTrue(GePriceService.parseHistory("nope").isEmpty());
        assertTrue(GePriceService.parseOpenSearch("{}").isEmpty());
    }

    @Test
    void trendsComeFromHistory() {
        Outcome o = service(new AtomicInteger(), false).lookup("Dragon bones");
        assertEquals(Status.FOUND, o.status());
        assertNotNull(o.price().week().percent());
        assertNotNull(o.price().month().percent());
        assertTrue(o.price().week().thenPrice() > 0);
    }

    @Test
    void trendUsesNewestPointAtLeastThatOld() {
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        var history = java.util.List.of(
                new GePriceService.Point(now.minusSeconds(40L * 86400).toEpochMilli(), 50),
                new GePriceService.Point(now.minusSeconds(8L * 86400).toEpochMilli(), 80),
                new GePriceService.Point(now.minusSeconds(2L * 86400).toEpochMilli(), 99));
        var week = GePriceService.trend(100, history, now, 7);
        assertEquals(80, week.thenPrice());
        assertEquals(25.0, week.percent(), 0.001);
        assertEquals(100.0, GePriceService.trend(100, history, now, 30).percent(), 0.001);
        assertNull(GePriceService.trend(100, history, now, 60).percent());
    }

    @Test
    void suggestionsParse() {
        var names = service(new AtomicInteger(), false).suggest("dragon bo", 5);
        assertTrue(names.contains("Dragon bones"));
        assertTrue(service(new AtomicInteger(), false).suggest("d", 5).isEmpty());
    }

    @Test
    void sourceDownIsUnavailableAndNeverCached() {
        AtomicInteger calls = new AtomicInteger();
        var svc = service(calls, true);
        assertEquals(Status.UNAVAILABLE, svc.lookup("Dragon bones").status());
        assertEquals(Status.UNAVAILABLE, svc.lookup("Dragon bones").status());
        assertEquals(2, calls.get());
    }

    @Test
    void repeatLookupsAreCached() {
        AtomicInteger calls = new AtomicInteger();
        var svc = service(calls, false);
        svc.lookup("Dragon bones");
        int first = calls.get();
        svc.lookup("Dragon bones");
        assertEquals(first, calls.get());
    }
}
