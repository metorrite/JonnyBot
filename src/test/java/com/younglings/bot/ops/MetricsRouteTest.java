package com.younglings.bot.ops;

import com.sun.net.httpserver.HttpServer;
import com.younglings.bot.config.BotConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code GET /metrics} on the internal API port (T-674): off by default, then token and private network only. */
class MetricsRouteTest {
    private static final String TOKEN = "k".repeat(32);

    private static int status(MetricsRoute route, String method, String path, String... headers) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(route.prefix(), route::handle);
        server.start();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"));
                assertEquals("rc_up 1\n", r.body());
            }
            return r.statusCode();
        } finally {
            server.stop(0);
        }
    }

    private static MetricsRoute route(boolean enabled, String token) {
        BotConfig config = mock(BotConfig.class);
        when(config.isMetricsEnabled()).thenReturn(enabled);
        when(config.getMetricsToken()).thenReturn(token);
        OpsMetrics metrics = mock(OpsMetrics.class);
        when(metrics.render()).thenReturn("rc_up 1\n");
        return new MetricsRoute(metrics, config);
    }

    @Test
    void offByDefault() throws Exception {
        MetricsRoute off = route(false, TOKEN);
        assertEquals(404, status(off, "GET", "/metrics", "Authorization", "Bearer " + TOKEN));
        assertTrue(off.authorized(null, null), "the internal secret is never asked for");
    }

    @Test
    void onlyWithTheTokenOverThePrivateNetwork() throws Exception {
        MetricsRoute on = route(true, TOKEN);
        assertEquals(200, status(on, "GET", "/metrics", "Authorization", "Bearer " + TOKEN));
        assertEquals(404, status(on, "GET", "/metrics"));
        assertEquals(404, status(on, "GET", "/metrics", "Authorization", "Bearer " + "z".repeat(32)));
        assertEquals(404, status(on, "GET", "/metrics", "Authorization", "Bearer " + TOKEN, "X-Forwarded-For", "203.0.113.9"));
        assertEquals(404, status(on, "GET", "/metricsx", "Authorization", "Bearer " + TOKEN));
        assertEquals(404, status(on, "POST", "/metrics", "Authorization", "Bearer " + TOKEN));
        assertEquals(404, status(route(true, null), "GET", "/metrics", "Authorization", "Bearer " + TOKEN), "no usable token, no metrics");
    }
}
