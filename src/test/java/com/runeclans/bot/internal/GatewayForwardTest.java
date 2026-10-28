package com.runeclans.bot.internal;

import com.runeclans.bot.ops.OpsTelemetry;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-746: the api role's internal API passes the routes that need servers or members on to the gateway process and hands back exactly what
 * it said, so the website gets the same answer as from one process; when the gateway can't answer it says 503 "try again", never "not in
 * that server". Real HTTP on loopback, no Discord.
 */
class GatewayForwardTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    @Test
    void routesThatNeedServersOrMembersGoToTheGatewayAndPicturesAndMetricsStayHere() {
        for (String route : List.of("/internal/online-members", "/internal/member", "/internal/nickname", "/internal/color-role", "/internal/events",
                "/internal/admin/", "/internal/site/", "/internal/directory/", "/internal/me/", "/internal/plugin/", "/internal/forms/",
                "/internal/linked-roles/", "/internal/billing/", "/internal/appeals/")) {
            assertFalse(GatewayForward.local(route), route + " needs the gateway's servers or members");
        }
        assertTrue(GatewayForward.local("/internal/image/"));
        assertTrue(GatewayForward.local("/internal/wheel-gif/"));
        assertTrue(GatewayForward.local("/metrics"), "each process reports its own numbers");
    }

    @Test
    void theWebsiteGetsTheGatewaysAnswerAsItCame() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer gateway = server(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " secret=" + exchange.getRequestHeaders().getFirst("X-Internal-Secret")
                    + " guild=" + exchange.getRequestHeaders().getFirst("X-Guild-Id") + " id=" + exchange.getRequestHeaders().getFirst("X-Request-Id")
                    + " body=" + body);
            byte[] answer = "{\"online\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("X-Answered-By", "gateway");
            exchange.sendResponseHeaders(201, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        HttpServer api = front(new GatewayForward(url(gateway)));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/admin/members?q=a%20b&page=2"))
                .header("X-Internal-Secret", "s3cret").header("X-Guild-Id", "42").header("X-Request-Id", "req-12345678")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Zezima\"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(201, answer.statusCode());
        assertEquals("{\"online\":3}", answer.body());
        assertEquals("gateway", answer.headers().firstValue("X-Answered-By").orElse(null));
        assertEquals("application/json; charset=utf-8", answer.headers().firstValue("Content-Type").orElse(null));
        assertEquals(List.of("req-12345678"), answer.headers().allValues("X-Request-Id"), "one request id, the same on both sides");
        assertEquals("POST /internal/admin/members?q=a%20b&page=2 secret=s3cret guild=42 id=req-12345678 body={\"name\":\"Zezima\"}", seen.get());
    }

    @Test
    void anEmptyAnswerStaysEmpty() throws Exception {
        HttpServer gateway = server(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway)));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/admin/thing")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(204, answer.statusCode());
        assertEquals("", answer.body());
    }

    @Test
    void aGatewayThatIsDownMeansTryAgainNeverNotInTheServer() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        HttpServer api = front(new GatewayForward("http://127.0.0.1:" + closed + "/"));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member?id=7")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(503, answer.statusCode());
        assertTrue(DataObject.fromJson(answer.body()).getString("error").contains("Try again"), answer.body());
    }

    @Test
    void aGatewayTooSlowToAnswerMeansTryAgainBeforeTheWebsiteGivesUp() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer gateway = server(exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway), Duration.ofMillis(300), Duration.ofSeconds(1)));
        try {
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/online-members")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, answer.statusCode());
        } finally {
            release.countDown();
        }
        assertTrue(GatewayForward.TIMEOUT.compareTo(Duration.ofSeconds(8)) < 0, "the website gives up after 8 s");
    }

    @Test
    void aLocalRouteIsAnsweredHereEvenWithTheGatewayDown() throws Exception {
        GatewayForward forward = new GatewayForward("http://127.0.0.1:9/");
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(api);
        OpsTelemetry.context(api, "/internal/image/", forward.handlerFor("/internal/image/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }));
        api.start();

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/image/abc")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode());
        assertNull(answer.headers().firstValue("X-Answered-By").orElse(null));
    }

    /** The api process's side: every route the way {@code InternalApiServer} registers it, through the request filter. */
    private HttpServer front(GatewayForward forward) throws IOException {
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(api);
        for (String route : List.of("/internal/admin/", "/internal/member", "/internal/online-members")) {
            OpsTelemetry.context(api, route, forward.handlerFor(route, exchange -> {
                throw new AssertionError("the api process must not answer " + route + " itself");
            }));
        }
        api.start();
        return api;
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
