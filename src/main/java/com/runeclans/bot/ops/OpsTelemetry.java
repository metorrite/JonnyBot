package com.runeclans.bot.ops;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Map;

/**
 * The bot's request telemetry (T-674): one shared {@link MetricRegistry}, the internal API's request filter, and the Discord 429 counter.
 * Static, so the internal API and the JDA builder need no new constructor arguments. With nothing switched on ({@link #configure} never
 * called, or called with everything off) the filter only handles the request id: it keeps a well-formed incoming {@code X-Request-Id}
 * (or makes one), echoes it, and keeps it in the logging context so a call on to the hub carries the same id.
 */
public final class OpsTelemetry {
    private static final Logger log = LoggerFactory.getLogger(OpsTelemetry.class);
    static final String REQUESTS = "rc_http_requests_total";
    static final String LATENCY = "rc_http_request_duration_seconds";
    public static final String DISCORD_429 = "rc_discord_rest_429_total";

    private static final MetricRegistry REGISTRY = new MetricRegistry();
    private static volatile boolean metrics;
    private static volatile boolean requestLog;

    private OpsTelemetry() {}

    public static MetricRegistry registry() {
        return REGISTRY;
    }

    /** Switches request counting ({@code METRICS_ENABLED}) and the request log ({@code REQUEST_LOG}). */
    public static void configure(boolean countRequests, boolean logRequests) {
        metrics = countRequests;
        requestLog = logRequests;
    }

    /** The request id of the internal API request running on this thread, or null. */
    public static String currentRequestId() {
        return RequestIds.accept(MDC.get(RequestIds.MDC_KEY));
    }

    /** {@code server.createContext(path, handler)} with the request filter, labelled with the context's path (never the raw path). */
    public static HttpContext context(HttpServer server, String path, HttpHandler handler) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(filter(path));
        return context;
    }

    static Filter filter(String route) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                long started = System.nanoTime();
                String requestId = RequestIds.orNew(exchange.getRequestHeaders().getFirst(RequestIds.HEADER));
                exchange.getResponseHeaders().set(RequestIds.HEADER, requestId);
                MDC.put(RequestIds.MDC_KEY, requestId);
                try {
                    chain.doFilter(exchange);
                } finally {
                    MDC.remove(RequestIds.MDC_KEY);
                    answered(exchange.getRequestMethod(), routeLabel(route, exchange.getRequestURI().getPath()), exchange.getResponseCode(),
                            System.nanoTime() - started, requestId);
                }
            }

            @Override
            public String description() {
                return "request id, metrics and request log";
            }
        };
    }

    /**
     * The label for a request: the context's path, plus the next path segment when the context ends in {@code /} and that segment is a plain
     * word ({@code /internal/admin/guilds}); a segment with a digit or anything unusual (an id, a token) becomes {@code *}.
     */
    static String routeLabel(String context, String path) {
        if (!context.endsWith("/") || path == null || !path.startsWith(context)) return context;
        String rest = path.substring(context.length());
        int slash = rest.indexOf('/');
        String segment = slash < 0 ? rest : rest.substring(0, slash);
        if (segment.isEmpty()) return context;
        return context + (segment.matches("[a-z][a-z-]{0,31}") ? segment : "*");
    }

    static void answered(String method, String route, int status, long nanos, String requestId) {
        if (metrics) {
            REGISTRY.inc(REQUESTS, "Internal API requests answered, by method, route prefix and status class.",
                    Map.of("method", method, "route", route, "status", statusClass(status)));
            REGISTRY.observe(LATENCY, "Time to answer an internal API request, by method and route prefix.", MetricRegistry.LATENCY_BUCKETS,
                    Map.of("method", method, "route", route), nanos / 1e9);
        }
        if (requestLog) log.info("request id={} {} {} {} {}ms", requestId, method, route, status, nanos / 1_000_000);
    }

    static String statusClass(int status) {
        return status < 100 || status > 599 ? "other" : (status / 100) + "xx";
    }

    /**
     * Counts every 429 answer Discord's REST API gives the bot. JDA's own rate limiter normally keeps them away, so a run of them means a
     * shared (Cloudflare) limit or a bug. Added to JDA's HTTP client only when metrics or the ops alerts are on.
     */
    public static Interceptor discord429Counter() {
        return chain -> {
            okhttp3.Response response = chain.proceed(chain.request());
            if (response.code() == 429) {
                String scope = response.header("X-RateLimit-Scope");
                REGISTRY.inc(DISCORD_429, "Discord REST answers with status 429, by scope (user, global, shared).",
                        Map.of("scope", scope == null || !scope.matches("[a-z]{1,16}") ? "unknown" : scope));
            }
            return response;
        };
    }
}
