package com.runeclans.bot.internal;

import com.runeclans.bot.ops.OpsTelemetry;
import com.runeclans.bot.ops.RequestIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The api role's way to the servers and members it can't see (T-746, docs/SCALING-STATE.md section 1.1). An api process has a REST-only
 * Discord connection and no cache, so a route that reads a server, its members, roles or channels would answer as if the bot weren't
 * there. Instead the api process passes the request on to the gateway process's internal API on the private network
 * ({@code GATEWAY_INTERNAL_URL}) and hands back its status, headers and body, so the website sees what one process ({@code BOT_ROLE=all})
 * would have said. The gateway checks the secret and everything else as usual; the api checks the caller's secret too, first, so a stranger
 * costs the gateway nothing.
 *
 * <p>Every route is passed on except the ones in {@link #LOCAL}, known to need no Discord state (stored pictures, metrics), which the api
 * process answers itself. A route moves to {@link #LOCAL} only once its handler is known to read nothing from the gateway's cache.
 *
 * <p>Answers when the gateway doesn't: no connection is {@code 503} "try again" (nothing happened there); no answer in time to a read is the
 * same; no answer in time to a change is {@code 503} with code {@link #NO_ANSWER}, because the gateway may still be carrying it out and the
 * website must not repeat it blindly. Never a made-up "not in that server".
 */
public final class GatewayForward {
    private static final Logger log = LoggerFactory.getLogger(GatewayForward.class);

    /**
     * Routes (as registered) the api process answers itself: stored pictures (uploaded images and wheel GIFs, read from Postgres by their
     * token, no Discord state) and its own metrics (each process reports its own numbers).
     */
    static final Set<String> LOCAL = Set.of("/internal/image/", "/internal/wheel-gif/", "/metrics");
    /** The website gives up after 8 s, so the gateway gets a little less, leaving time to answer "try again". */
    static final Duration TIMEOUT = Duration.ofSeconds(7);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** Above the largest body the website sends (a 9 MB wheel GIF, base64 in JSON); anything bigger is refused before it is read. */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    /**
     * Marks a request that one process already passed on. A process that would pass it on again answers {@code 508} instead, so a
     * {@code GATEWAY_INTERNAL_URL} pointing at an api process can't bounce requests around. Never trusted for anything else.
     */
    static final String HOP_HEADER = "X-RC-Hop";
    /** The code on a 503 for a change the gateway didn't answer in time: it may still have happened, so check before trying again. */
    static final String NO_ANSWER = "GATEWAY_NO_ANSWER";
    /** Headers the JDK's client sets itself (it refuses them), plus hop-by-hop ones that describe one connection, not the request. */
    private static final Set<String> NOT_PASSED_ON = Set.of("connection", "content-length", "date", "expect", "from", "host", "upgrade", "via",
            "warning", "keep-alive", "transfer-encoding", "te", "trailer", "proxy-connection", "http2-settings");

    private final URI gateway;
    private final HttpClient client;
    private final Duration timeout;

    public GatewayForward(String gatewayInternalUrl) {
        this(gatewayInternalUrl, TIMEOUT, CONNECT_TIMEOUT);
    }

    GatewayForward(String gatewayInternalUrl, Duration timeout, Duration connectTimeout) {
        this.gateway = parse(gatewayInternalUrl);
        if (gateway == null) throw new IllegalArgumentException("GATEWAY_INTERNAL_URL must be an http(s) address with a host");
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The gateway's base address ({@code http(s)://host[:port][/path]}, no trailing slash), or null when {@code raw} isn't one. */
    public static URI parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String base = raw.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            URI uri = new URI(base);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) return null;
            return uri;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Whether a request to this registered route is answered by this (api) process rather than passed on to the gateway. */
    static boolean local(String route) {
        return LOCAL.contains(route);
    }

    /**
     * {@code handler} for a route this process answers itself; otherwise a handler that checks the caller with {@code authorized} (the
     * route's own rule: the internal secret, or a module's) and passes the request on to the gateway.
     */
    HttpHandler handlerFor(String route, HttpHandler handler, Predicate<HttpExchange> authorized) {
        if (local(route)) return handler;
        return exchange -> {
            try {
                if (!authorized.test(exchange)) {
                    answer(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                    return;
                }
                forward(exchange);
            } catch (RuntimeException e) {
                // never a dropped connection: the website reads a plain answer
                log.warn("Passing {} on to the gateway failed: {}", route, e.getClass().getSimpleName());
                tryAgain(exchange);
            }
        };
    }

    /** Passes one request on to the gateway and answers with whatever it said (see the class notes for when it can't). */
    void forward(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().containsKey(HOP_HEADER)) {
            log.warn("A request reached this api process from another one: GATEWAY_INTERNAL_URL must point at the gateway process");
            answer(exchange, 508, DataObject.empty().put("error", "The bot is set up wrong. Try again later."));
            return;
        }
        byte[] body = body(exchange);
        if (body == null) {
            answer(exchange, 413, DataObject.empty().put("error", "That is too large."));
            return;
        }
        HttpRequest request;
        try {
            request = request(exchange, body);
        } catch (IllegalArgumentException e) {
            // a method or header name the client can't send: the caller's mistake, not the gateway's
            answer(exchange, 400, DataObject.empty().put("error", "Bad request"));
            return;
        }
        HttpResponse<byte[]> answer;
        try {
            answer = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            log.warn("The gateway can't be reached for {} {}: {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e.getClass().getSimpleName());
            tryAgain(exchange);
            return;
        } catch (HttpTimeoutException e) {
            log.warn("The gateway didn't answer {} {} in time", exchange.getRequestMethod(), exchange.getRequestURI().getPath());
            if (reads(exchange.getRequestMethod())) tryAgain(exchange);
            else noAnswer(exchange);
            return;
        } catch (IOException e) {
            log.warn("The gateway didn't answer {} {}: {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e.getClass().getSimpleName());
            if (reads(exchange.getRequestMethod())) tryAgain(exchange);
            else noAnswer(exchange);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tryAgain(exchange);
            return;
        }
        for (Map.Entry<String, List<String>> header : answer.headers().map().entrySet()) {
            String name = header.getKey();
            if (name.startsWith(":") || NOT_PASSED_ON.contains(name.toLowerCase(Locale.ROOT))) continue;
            // set, not add: the request filter has already put this process's request id on the answer
            exchange.getResponseHeaders().put(name, List.copyOf(header.getValue()));
        }
        byte[] payload = answer.body();
        boolean noBody = payload.length == 0 || answer.statusCode() == 204 || answer.statusCode() == 304
                || "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.sendResponseHeaders(answer.statusCode(), noBody ? -1 : payload.length);
        if (noBody) {
            exchange.close();
            return;
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** The request body, or null when it is over {@link #MAX_BODY_BYTES} (judged from Content-Length first, so a big one isn't read). */
    private static byte[] body(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.strip()) > MAX_BODY_BYTES) return null;
            } catch (NumberFormatException ignored) {
                // the server already refused a broken length; read with the cap below
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private HttpRequest request(HttpExchange exchange, byte[] body) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        URI target = URI.create(gateway + exchange.getRequestURI().getRawPath() + (rawQuery == null ? "" : "?" + rawQuery));
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(timeout)
                .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            String name = header.getKey();
            if (NOT_PASSED_ON.contains(name.toLowerCase(Locale.ROOT)) || name.equalsIgnoreCase(RequestIds.HEADER)) continue;
            for (String value : header.getValue()) request.header(name, value);
        }
        // the same request id on both sides, so one website request can be followed through both processes' logs
        String requestId = OpsTelemetry.currentRequestId();
        if (requestId != null) request.header(RequestIds.HEADER, requestId);
        request.header(HOP_HEADER, "1");
        return request.build();
    }

    /** A read can be asked again safely; anything else may already have happened. */
    private static boolean reads(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static void tryAgain(HttpExchange exchange) throws IOException {
        answer(exchange, 503, DataObject.empty().put("error", "The bot is busy. Try again in a moment."));
    }

    private static void noAnswer(HttpExchange exchange) throws IOException {
        answer(exchange, 503, DataObject.empty().put("error", "The bot didn't answer in time, so this may or may not have been saved. Check before trying again.")
                .put("code", NO_ANSWER));
    }

    private static void answer(HttpExchange exchange, int status, DataObject payload) throws IOException {
        InternalApiServer.sendJson(exchange, status, payload);
    }
}
