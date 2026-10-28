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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The api role's way to the servers and members it can't see (T-746, docs/SCALING-STATE.md section 5). An api process has a REST-only
 * Discord connection and no cache, so a route that reads a server, its members, roles or channels would answer as if the bot weren't
 * there. Instead the api process passes the whole request on to the gateway process's internal API on the private network
 * ({@code GATEWAY_INTERNAL_URL}) and hands back its answer as it came: same status, headers and body, so the website sees exactly what
 * one process ({@code BOT_ROLE=all}) would have said. The gateway checks the secret and everything else as usual.
 *
 * <p>Every route is passed on except the few in {@link #LOCAL} that are known to need no Discord state (stored pictures, metrics), which
 * the api process answers itself. A route moves to {@link #LOCAL} only once its handler is known to read nothing from the gateway's cache.
 *
 * <p>When the gateway can't be reached in time (restarting, overloaded, the network), the answer is {@code 503} "try again", never a
 * made-up "not in that server": the website already treats a 503 as "try again in a moment".
 */
public final class GatewayForward {
    private static final Logger log = LoggerFactory.getLogger(GatewayForward.class);

    /**
     * Route prefixes the api process answers itself: stored pictures (uploaded images and wheel GIFs, read from Postgres by their token, no
     * Discord state) and its own metrics (each process reports its own numbers).
     */
    static final List<String> LOCAL = List.of("/internal/image/", "/internal/wheel-gif/", "/metrics");
    /** The website gives up after 8 s, so the gateway gets a little less, leaving time to answer "try again". */
    static final Duration TIMEOUT = Duration.ofSeconds(7);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** Headers the JDK's client sets itself (it refuses them), plus hop-by-hop ones that describe one connection, not the request. */
    private static final Set<String> NOT_PASSED_ON = Set.of("connection", "content-length", "date", "expect", "from", "host", "upgrade", "via",
            "warning", "keep-alive", "transfer-encoding", "te", "trailer", "proxy-connection");

    private final URI gateway;
    private final HttpClient client;
    private final Duration timeout;

    public GatewayForward(String gatewayInternalUrl) {
        this(gatewayInternalUrl, TIMEOUT, CONNECT_TIMEOUT);
    }

    GatewayForward(String gatewayInternalUrl, Duration timeout, Duration connectTimeout) {
        String base = gatewayInternalUrl.strip();
        this.gateway = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Whether a request to this route is answered by this (api) process rather than passed on to the gateway. */
    static boolean local(String route) {
        return LOCAL.stream().anyMatch(route::startsWith);
    }

    /** {@code handler} for a route this process answers itself; otherwise a handler that passes the request on to the gateway. */
    HttpHandler handlerFor(String route, HttpHandler handler) {
        return local(route) ? handler : this::forward;
    }

    /** Passes one request on to the gateway and answers with whatever it said, or 503 "try again" when it can't be reached in time. */
    void forward(HttpExchange exchange) throws IOException {
        HttpResponse<byte[]> answer;
        try {
            answer = client.send(request(exchange), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("The gateway didn't answer {} {}: {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e.toString());
            tryAgain(exchange);
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
        byte[] body = answer.body();
        boolean noBody = body.length == 0 || answer.statusCode() == 204 || answer.statusCode() == 304
                || "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.sendResponseHeaders(answer.statusCode(), noBody ? -1 : body.length);
        if (noBody) {
            exchange.close();
            return;
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private HttpRequest request(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
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
        return request.build();
    }

    private static void tryAgain(HttpExchange exchange) throws IOException {
        InternalApiServer.sendJson(exchange, 503, DataObject.empty().put("error", "The bot is busy. Try again in a moment."));
    }
}
