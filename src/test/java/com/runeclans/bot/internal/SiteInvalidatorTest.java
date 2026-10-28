package com.runeclans.bot.internal;

import com.sun.net.httpserver.HttpServer;
import com.runeclans.bot.config.BotConfig;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteInvalidatorTest {
    /** A throwaway made-up value, built at runtime: it is not a real secret. */
    private static final String SECRET = "test-" + System.nanoTime();

    private HttpServer server;
    private final BlockingQueue<String[]> received = new LinkedBlockingQueue<>();
    private String siteUrl;

    @BeforeEach
    void startSite() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/revalidate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new String[]{exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("X-Internal-Secret"), body});
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        siteUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static SiteInvalidator invalidatorFor(String siteUrl, String secret, long delayMillis) {
        return invalidatorFor(siteUrl, null, secret, delayMillis);
    }

    private static SiteInvalidator invalidatorFor(String siteUrl, String clanSitesUrl, String secret, long delayMillis) {
        BotConfig config = mock(BotConfig.class);
        when(config.getSiteUrl()).thenReturn(siteUrl);
        when(config.getClanSitesUrl()).thenReturn(clanSitesUrl);
        when(config.getInternalApiSecret()).thenReturn(secret);
        SiteInvalidator invalidator = new SiteInvalidator(config, null);
        invalidator.delayMillis = delayMillis;
        return invalidator;
    }

    /** Server ids made up at runtime (never real ones): server A has the site "alpha-clan", server B "bravo", server C none. */
    private static final long GUILD_A = Long.parseLong("1".repeat(18));
    private static final long GUILD_B = Long.parseLong("2".repeat(18));
    private static final long GUILD_C = Long.parseLong("3".repeat(18));

    private static Optional<String> slugOf(long guildId) {
        if (guildId == GUILD_A) return Optional.of("alpha-clan");
        if (guildId == GUILD_B) return Optional.of("bravo");
        return Optional.empty();
    }

    /**
     * The tag lists of the calls that arrive within a short while. The calls are sent at once and can land in any order, so they are sorted:
     * shared tags first, then the per-site batches, largest first.
     */
    private List<List<Object>> callsWithin(long millis) throws InterruptedException {
        List<List<Object>> calls = new ArrayList<>();
        String[] call = received.poll(5, TimeUnit.SECONDS);
        while (call != null) {
            assertEquals(SECRET, call[1]);
            calls.add(DataObject.fromJson(call[2]).getArray("tags").toList());
            call = received.poll(millis, TimeUnit.MILLISECONDS);
        }
        calls.sort(java.util.Comparator.<List<Object>, Boolean>comparing(SiteInvalidatorTest::perSite).thenComparing(l -> -l.size()));
        return calls;
    }

    private static boolean perSite(List<?> tags) {
        return tags.stream().anyMatch(t -> String.valueOf(t).matches("^(site:[a-z0-9-]+:.+|clan-site:.+|site:alpha-clan)$"));
    }

    @AfterEach
    void stopSite() {
        SiteInvalidator.instance = null;
        server.stop(0);
    }

    @Test
    void manySignalsBecomeOneSignedCallWithEveryTagOnce() throws Exception {
        SiteInvalidator invalidator = invalidatorFor(siteUrl, SECRET, 50);
        for (int i = 0; i < 200; i++) invalidator.queue(SiteInvalidator.Area.ROSTER); // a roster sync writing 200 members
        invalidator.queue(SiteInvalidator.Area.POLLS, SiteInvalidator.Area.HUB);

        String[] call = received.poll(5, TimeUnit.SECONDS);
        assertEquals("POST", call[0]);
        assertEquals(SECRET, call[1]);
        List<Object> tags = DataObject.fromJson(call[2]).getArray("tags").toList();
        assertTrue(tags.containsAll(List.of("site:members", "site:polls", "hub:clans", "hub:events", "hub:leaderboards", "site:overview")), tags.toString());
        assertEquals(tags.size(), tags.stream().distinct().count(), "each tag once");
        assertNull(received.poll(300, TimeUnit.MILLISECONDS), "one call for all of it");
    }

    @Test
    void aLaterChangeSendsALaterCall() throws Exception {
        SiteInvalidator invalidator = invalidatorFor(siteUrl, SECRET, 30);
        invalidator.queue(SiteInvalidator.Area.NEWS);
        assertEquals("{\"tags\":[\"site:news\"]}", received.poll(5, TimeUnit.SECONDS)[2]);
        invalidator.queue(SiteInvalidator.Area.OPTIONS);
        assertEquals("{\"tags\":[\"site:options\"]}", received.poll(5, TimeUnit.SECONDS)[2]);
    }

    @Test
    void withoutASiteOrASecretNothingIsSentAndNothingFails() throws Exception {
        invalidatorFor(null, SECRET, 10).queue(SiteInvalidator.Area.POLLS);
        invalidatorFor(siteUrl, null, 10).queue(SiteInvalidator.Area.POLLS);
        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
        SiteInvalidator.signal(SiteInvalidator.Area.POLLS); // no instance registered by these: a no-op, not an error
    }

    @Test
    void aWebsiteThatIsDownNeverBreaksTheWrite() throws Exception {
        server.stop(0);
        SiteInvalidator invalidator = invalidatorFor(siteUrl, SECRET, 10);
        invalidator.queue(SiteInvalidator.Area.POLLS); // must return at once and only log
        Thread.sleep(300);
        assertNull(received.poll());
    }

    @Test
    void everyTagUsesTheNamesTheWebsiteAccepts() {
        for (SiteInvalidator.Area area : SiteInvalidator.Area.values()) {
            for (String tag : area.tags()) assertTrue(tag.matches("^(site|hub):[a-z0-9-]{1,40}$"), tag);
        }
    }

    @Test
    void aWriteInOneServerClearsThatServersSiteAndNoOther() throws Exception {
        SiteInvalidator invalidator = invalidatorFor(null, siteUrl, SECRET, 50);
        invalidator.slugOf = SiteInvalidatorTest::slugOf;
        for (int i = 0; i < 20; i++) invalidator.queue(() -> OptionalLong.of(GUILD_A), SiteInvalidator.Area.POLLS); // 20 votes, one poll

        List<List<Object>> calls = callsWithin(300);
        assertEquals(2, calls.size(), "the shared tags and the per-site tags, each once: " + calls);
        assertEquals(List.of("site:polls"), calls.get(0));
        assertEquals(List.of("site:alpha-clan:polls"), calls.get(1));
        assertFalse(calls.toString().contains("bravo"));
    }

    @Test
    void perSiteTagsNeverGoToTheOldSite() throws Exception {
        HttpServer clanSites = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        BlockingQueue<String> clanSitesBodies = new LinkedBlockingQueue<>();
        clanSites.createContext("/api/revalidate", exchange -> {
            clanSitesBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        clanSites.start();
        try {
            SiteInvalidator invalidator = invalidatorFor(siteUrl, "http://127.0.0.1:" + clanSites.getAddress().getPort(), SECRET, 50);
            invalidator.slugOf = SiteInvalidatorTest::slugOf;
            invalidator.queue(() -> OptionalLong.of(GUILD_B), SiteInvalidator.Area.EVENTS);

            assertEquals(List.of(List.of("hub:clans", "hub:events", "site:events")), callsWithin(300), "SITE_URL gets only the shared tags");
            List<String> bodies = new ArrayList<>();
            for (String b = clanSitesBodies.poll(5, TimeUnit.SECONDS); b != null; b = clanSitesBodies.poll(300, TimeUnit.MILLISECONDS)) bodies.add(b);
            bodies.sort(java.util.Comparator.comparing(b -> b.contains("bravo"))); // any order: shared first
            assertEquals(2, bodies.size(), bodies.toString());
            assertTrue(bodies.get(0).contains("hub:events") && !bodies.get(0).contains("bravo"), bodies.get(0));
            assertTrue(bodies.get(1).contains("site:bravo:calendarEvents") && bodies.get(1).contains("site:bravo:eventStandings"), bodies.get(1));
        } finally {
            clanSites.stop(0);
        }
    }

    @Test
    void aHubChangeClearsTheWholeSiteAndAServerWithoutASiteOnlyTheSharedTags() throws Exception {
        SiteInvalidator invalidator = invalidatorFor(null, siteUrl, SECRET, 50);
        invalidator.slugOf = SiteInvalidatorTest::slugOf;
        invalidator.queue(() -> OptionalLong.of(GUILD_A), SiteInvalidator.Area.HUB);
        invalidator.queue(() -> OptionalLong.of(GUILD_C), SiteInvalidator.Area.ROSTER);
        invalidator.queue(() -> { throw new IllegalStateException("database down"); }, SiteInvalidator.Area.SIGNUPS); // only logged

        List<List<Object>> calls = callsWithin(300);
        assertEquals(2, calls.size(), calls.toString());
        assertTrue(calls.get(0).containsAll(List.of("hub:sites", "site:members", "site:signups")), calls.get(0).toString());
        assertEquals(List.of("clan-site:alpha-clan", "site:alpha-clan"), calls.get(1));
    }

    @Test
    void manySitesAreSentInBatchesTheWebsiteAccepts() throws Exception {
        SiteInvalidator invalidator = invalidatorFor(null, siteUrl, SECRET, 50);
        invalidator.slugOf = guildId -> Optional.of("clan-" + guildId);
        for (long g = 1; g <= 10; g++) {
            long guild = g;
            invalidator.queue(() -> OptionalLong.of(guild), SiteInvalidator.Area.ROSTER); // 5 keys each: 50 per-site tags
        }
        List<List<Object>> calls = callsWithin(300);
        assertEquals(3, calls.size(), "shared, then 30 + 20 per-site tags");
        assertEquals(SiteInvalidator.MAX_TAGS, calls.get(1).size());
        assertEquals(20, calls.get(2).size());
    }

    @Test
    void aSiteAddressTheWebsiteWouldRefuseIsLeftOut() throws Exception {
        SiteInvalidator invalidator = invalidatorFor(null, siteUrl, SECRET, 50);
        invalidator.slugOf = guildId -> Optional.of("Not A Slug");
        invalidator.queue(() -> OptionalLong.of(GUILD_A), SiteInvalidator.Area.POLLS);
        assertEquals(List.of(List.of("site:polls")), callsWithin(300));
    }

    @Test
    void everyPerSiteTagUsesTheShapeTheClanSitesAccept() {
        for (SiteInvalidator.Area area : SiteInvalidator.Area.values()) {
            for (String tag : area.siteTags("alpha-clan")) {
                assertTrue(tag.matches("^site:alpha-clan:[A-Za-z0-9-]{1,40}$") || tag.equals("site:alpha-clan") || tag.equals("clan-site:alpha-clan"), tag);
            }
        }
    }
}
