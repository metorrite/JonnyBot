package com.younglings.bot.ops;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.internal.InternalRouteModule;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /metrics} on the internal API port (T-674): the Prometheus text from {@link OpsMetrics}. Only with {@code METRICS_ENABLED=on},
 * {@code METRICS_TOKEN} (24+ characters) sent as a bearer token, and a request that didn't pass a public edge ({@link MetricsAccess}); every
 * other request gets the same plain 404 as an unknown path. It never needs or accepts the internal API secret.
 */
@BService
public class MetricsRoute implements InternalRouteModule {
    private final OpsMetrics metrics;
    private final boolean enabled;
    private final String token;

    public MetricsRoute(OpsMetrics metrics, BotConfig config) {
        this.metrics = metrics;
        this.enabled = config.isMetricsEnabled();
        this.token = config.getMetricsToken();
    }

    /** The exact path {@code /metrics}: the server's prefix match also hands over {@code /metricsx}, which {@link #handle} refuses. */
    @Override
    public String prefix() {
        return "/metrics";
    }

    /** The token is checked in {@link #handle}, so a refusal looks like a missing page rather than a 401. */
    @Override
    public boolean authorized(HttpExchange exchange, String internalSecret) {
        return true;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean ok = "GET".equals(exchange.getRequestMethod()) && "/metrics".equals(exchange.getRequestURI().getPath())
                && MetricsAccess.allowed(enabled, token, exchange.getRequestHeaders()::getFirst);
        byte[] body = (ok ? metrics.render() : "404 Not Found\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", ok ? "text/plain; version=0.0.4; charset=utf-8" : "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(ok ? 200 : 404, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
