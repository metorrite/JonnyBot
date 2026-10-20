package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpServer;
import com.younglings.bot.config.BotConfig;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        BotConfig config = mock(BotConfig.class);
        when(config.getSiteUrl()).thenReturn(siteUrl);
        when(config.getInternalApiSecret()).thenReturn(secret);
        SiteInvalidator invalidator = new SiteInvalidator(config);
        invalidator.delayMillis = delayMillis;
        return invalidator;
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
}
