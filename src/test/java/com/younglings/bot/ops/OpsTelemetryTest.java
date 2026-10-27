package com.younglings.bot.ops;

import com.sun.net.httpserver.HttpServer;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The internal API's request filter and the Discord 429 counter (T-674). */
class OpsTelemetryTest {
    @AfterEach
    void off() {
        OpsTelemetry.configure(false, false);
    }

    @Test
    void routeLabelsNeverCarryIds() {
        assertEquals("/internal/admin/guilds", OpsTelemetry.routeLabel("/internal/admin/", "/internal/admin/guilds/" + "1".repeat(18) + "/settings"));
        assertEquals("/internal/admin/*", OpsTelemetry.routeLabel("/internal/admin/", "/internal/admin/" + "1".repeat(18)));
        assertEquals("/internal/image/*", OpsTelemetry.routeLabel("/internal/image/", "/internal/image/AbC_token"));
        assertEquals("/internal/admin/", OpsTelemetry.routeLabel("/internal/admin/", "/internal/admin/"));
        assertEquals("/internal/member", OpsTelemetry.routeLabel("/internal/member", "/internal/member/whatever"));
        assertEquals("2xx", OpsTelemetry.statusClass(204));
        assertEquals("other", OpsTelemetry.statusClass(-1));
    }

    @Test
    void theRequestIdIsKeptForTheRequestAndEchoed() throws Exception {
        OpsTelemetry.configure(true, true);
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        OpsTelemetry.context(server, "/internal/opstest/", exchange -> {
            seen.set(OpsTelemetry.currentRequestId());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpResponse<String> kept = client.send(HttpRequest.newBuilder(URI.create(base + "/internal/opstest/thing/" + "2".repeat(18)))
                    .header("X-Request-Id", "web-req-0001").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("web-req-0001", kept.headers().firstValue("X-Request-Id").orElse(null));
            assertEquals("web-req-0001", seen.get(), "the handler (and a hub call it makes) sees the website's id");

            HttpResponse<String> made = client.send(HttpRequest.newBuilder(URI.create(base + "/internal/opstest/thing"))
                    .header("X-Request-Id", "bad id!").build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(made.headers().firstValue("X-Request-Id").orElse("").matches("[0-9a-f]{32}"));
            assertNull(OpsTelemetry.currentRequestId(), "nothing is left behind on the test thread");

            assertEquals(2, OpsTelemetry.registry().counter(OpsTelemetry.REQUESTS, Map.of("method", "GET", "route", "/internal/opstest/thing", "status", "2xx")));
            String text = OpsTelemetry.registry().render(java.util.List.of());
            assertTrue(!text.contains("2".repeat(18)), "no raw path in the labels");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discord429sAreCountedByScope() throws Exception {
        Interceptor counter = OpsTelemetry.discord429Counter();
        long before = OpsTelemetry.registry().counter(OpsTelemetry.DISCORD_429, Map.of("scope", "shared"));
        Request request = new Request.Builder().url("https://discord.invalid/api/v10/channels/x/messages").build();
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(request)).thenReturn(new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                .header("X-RateLimit-Scope", "shared").build());
        assertEquals(429, counter.intercept(chain).code());
        when(chain.proceed(request)).thenReturn(new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build());
        counter.intercept(chain);
        assertEquals(before + 1, OpsTelemetry.registry().counter(OpsTelemetry.DISCORD_429, Map.of("scope", "shared")));
    }
}
