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
    private static final String SECRET = "s3cret";
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
        assertFalse(GatewayForward.local("/metrics-x/"), "only the exact routes");
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

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/admin/thing")).header("X-Internal-Secret", SECRET).DELETE().build(),
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

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member?id=7")).header("X-Internal-Secret", SECRET).GET().build(),
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
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/online-members")).header("X-Internal-Secret", SECRET).GET().build(),
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
        }, exchange -> false));
        api.start();

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/image/abc")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode());
        assertNull(answer.headers().firstValue("X-Answered-By").orElse(null));
    }

    @Test
    void onlyAnHttpAddressWithAHostIsAGateway() {
        assertEquals("http://bot-gateway.internal:8081", String.valueOf(GatewayForward.parse(" http://bot-gateway.internal:8081/ ")));
        assertEquals("https://gw.internal/base", String.valueOf(GatewayForward.parse("https://gw.internal/base")));
        for (String wrong : new String[] {null, "", "bot-gateway.internal:8081", "http://bad host", "ftp://gw.internal", "http://", "http://gw?x=1",
                "http://user:pw@gw.internal"}) {
            assertNull(GatewayForward.parse(wrong), String.valueOf(wrong));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new GatewayForward("bot-gateway.internal:8081"));
    }

    @Test
    void aStrangerNeverReachesTheGateway() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer gateway = server(exchange -> {
            seen.set("called");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway)));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member?id=7"))
                .header("X-Internal-Secret", "wrong").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, answer.statusCode());
        assertNull(seen.get());
    }

    @Test
    void theGatewaysRefusalsComeBackAsTheyAre() throws Exception {
        for (int status : new int[] {401, 404, 503}) {
            HttpServer gateway = server(exchange -> {
                byte[] answer = ("{\"error\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, answer.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            });
            HttpServer api = front(new GatewayForward(url(gateway)));
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member"))
                    .header("X-Internal-Secret", SECRET).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(status, answer.statusCode());
            assertEquals("{\"error\":\"" + status + "\"}", answer.body());
        }
    }

    @Test
    void aGatewayAddressPointingAtAnotherApiProcessStopsAtOneHop() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer gateway = server(exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst(GatewayForward.HOP_HEADER));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway)));

        client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member")).header("X-Internal-Secret", SECRET).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("1", seen.get(), "every passed-on request is marked");

        HttpResponse<String> looped = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member"))
                .header("X-Internal-Secret", SECRET).header(GatewayForward.HOP_HEADER, "1").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(508, looped.statusCode());
    }

    @Test
    void aBodyOverTheCapIsRefusedBeforeTheGatewaySeesIt() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer gateway = server(exchange -> {
            seen.set("called");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway)));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/admin/upload"))
                .header("X-Internal-Secret", SECRET).POST(HttpRequest.BodyPublishers.ofByteArray(new byte[GatewayForward.MAX_BODY_BYTES + 1])).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(413, answer.statusCode());
        assertNull(seen.get());
    }

    @Test
    void headersOnlyTheConnectionMayCarryAreNotPassedOn() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer gateway = server(exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Host") + " " + exchange.getRequestHeaders().getFirst("Upgrade") + " "
                    + exchange.getRequestHeaders().getFirst("X-Custom"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway)));
        String sentAs;
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", api.getAddress().getPort())) {
            socket.getOutputStream().write(("GET /internal/member HTTP/1.1\r\nHost: evil.example\r\nUpgrade: h2c\r\nConnection: close\r\n"
                    + "X-Internal-Secret: " + SECRET + "\r\nX-Custom: kept\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            sentAs = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
        assertTrue(sentAs.startsWith("HTTP/1.1 200"), sentAs);
        assertEquals("127.0.0.1:" + gateway.getAddress().getPort() + " null kept", seen.get(), "the gateway's own host, no upgrade");
    }

    @Test
    void aHeadRequestGetsHeadersOnly() throws Exception {
        HttpServer gateway = server(exchange -> {
            exchange.getResponseHeaders().set("X-Answered-By", "gateway");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer api = front(new GatewayForward(url(gateway)));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/member"))
                .header("X-Internal-Secret", SECRET).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode());
        assertEquals("gateway", answer.headers().firstValue("X-Answered-By").orElse(null));
        assertEquals("", answer.body());
    }

    @Test
    void aChangeTheGatewayDidNotAnswerInTimeMayHaveHappened() throws Exception {
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
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/admin/nickname"))
                    .header("X-Internal-Secret", SECRET).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, answer.statusCode());
            assertEquals(GatewayForward.NO_ANSWER, DataObject.fromJson(answer.body()).getString("code"), "not the safe-to-repeat try again");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aGatewayThatIsDownMeansTryAgainEvenForAChange() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        HttpServer api = front(new GatewayForward("http://127.0.0.1:" + closed));

        HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(url(api) + "/internal/admin/nickname"))
                .header("X-Internal-Secret", SECRET).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(503, answer.statusCode());
        assertNull(DataObject.fromJson(answer.body()).getString("code", null), "nothing was sent, so it can safely be tried again");
    }

    /** The api process's side: every route the way {@code InternalApiServer} registers it, through the request filter. */
    private HttpServer front(GatewayForward forward) throws IOException {
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(api);
        for (String route : List.of("/internal/admin/", "/internal/member", "/internal/online-members")) {
            OpsTelemetry.context(api, route, forward.handlerFor(route, exchange -> {
                throw new AssertionError("the api process must not answer " + route + " itself");
            }, exchange -> SECRET.equals(exchange.getRequestHeaders().getFirst("X-Internal-Secret"))));
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
