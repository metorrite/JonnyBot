package com.runeclans.bot.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricRegistryTest {
    @Test
    void countersHistogramsAndGaugesInThePrometheusTextFormat() {
        MetricRegistry r = new MetricRegistry();
        r.inc("rc_test_total", "A test counter.", Map.of("route", "/v1/a", "method", "GET"));
        r.add("rc_test_total", "A test counter.", Map.of("route", "/v1/a", "method", "GET"), 2);
        r.observe("rc_test_seconds", "A test histogram.", new double[]{0.1, 1}, Map.of("route", "/x"), 0.05);
        r.observe("rc_test_seconds", "A test histogram.", new double[]{0.1, 1}, Map.of("route", "/x"), 0.5);
        r.observe("rc_test_seconds", "A test histogram.", new double[]{0.1, 1}, Map.of("route", "/x"), 7);
        String text = r.render(List.of(MetricRegistry.Gauge.of("rc_heap_bytes", "Heap.", 1024), new MetricRegistry.Gauge("rc_q", "Q.", Map.of("queue", "a\"b"), 1.5)));

        assertTrue(text.contains("# TYPE rc_test_total counter\nrc_test_total{method=\"GET\",route=\"/v1/a\"} 3\n"), text);
        assertTrue(text.contains("rc_test_seconds_bucket{route=\"/x\",le=\"0.1\"} 1\n"), text);
        assertTrue(text.contains("rc_test_seconds_bucket{route=\"/x\",le=\"1\"} 2\n"), text);
        assertTrue(text.contains("rc_test_seconds_bucket{route=\"/x\",le=\"+Inf\"} 3\n"), text);
        assertTrue(text.contains("rc_test_seconds_count{route=\"/x\"} 3\n"), text);
        assertTrue(text.contains("rc_test_seconds_sum{route=\"/x\"} 7.55\n"), text);
        assertTrue(text.contains("# TYPE rc_heap_bytes gauge\nrc_heap_bytes 1024\n"), text);
        assertTrue(text.contains("rc_q{queue=\"a\\\"b\"} 1.5\n"), text);
        assertEquals(3, r.counter("rc_test_total", Map.of("method", "GET", "route", "/v1/a")));
        assertEquals(3, r.counterTotal("rc_test_total"));
    }

    @Test
    void aFamilyKeepsABoundedNumberOfLabelSets() {
        MetricRegistry r = new MetricRegistry();
        for (int i = 0; i < MetricRegistry.MAX_SERIES + 5; i++) r.inc("rc_many_total", "Many.", Map.of("k", "v" + i));
        String text = r.render(List.of());
        assertTrue(text.contains("rc_metrics_dropped_series_total 5\n"), text);
        assertFalse(text.contains("v" + (MetricRegistry.MAX_SERIES + 1) + "\""));
    }

    @Test
    void requestIdsAreKeptOnlyWhenWellFormed() {
        assertEquals("abc-123_x.y:z", RequestIds.accept(" abc-123_x.y:z "));
        assertNull(RequestIds.accept("short"));
        assertNull(RequestIds.accept("has space in it"));
        assertNull(RequestIds.accept("line\nbreak-injected"));
        assertNull(RequestIds.accept("x".repeat(129)));
        assertEquals(32, RequestIds.orNew(null).length());
        assertTrue(RequestIds.orNew("bad id").matches("[0-9a-f]{32}"));
    }

    @Test
    void metricsNeedTheSwitchTheTokenAndThePrivateNetwork() {
        String token = "t".repeat(MetricsAccess.MIN_TOKEN_LENGTH);
        Map<String, String> good = Map.of("authorization", "Bearer " + token);
        assertTrue(MetricsAccess.allowed(true, token, h -> good.get(h.toLowerCase())));
        assertFalse(MetricsAccess.allowed(false, token, h -> good.get(h.toLowerCase())), "off by default");
        assertFalse(MetricsAccess.allowed(true, null, h -> good.get(h.toLowerCase())), "no token, no metrics");
        assertFalse(MetricsAccess.allowed(true, token, h -> Map.of("authorization", "Bearer wrong").get(h.toLowerCase())));
        assertFalse(MetricsAccess.allowed(true, token, h -> Map.<String, String>of().get(h.toLowerCase())));
        Map<String, String> proxied = Map.of("authorization", "Bearer " + token, "x-forwarded-for", "203.0.113.9");
        assertFalse(MetricsAccess.allowed(true, token, h -> proxied.get(h.toLowerCase())), "came through a public edge");
        assertNull(MetricsAccess.token("too-short"));
        assertNull(MetricsAccess.token(null));
        assertTrue(MetricsAccess.on("ON") && MetricsAccess.on("true") && MetricsAccess.on("1"));
        assertFalse(MetricsAccess.on("off") || MetricsAccess.on(null) || MetricsAccess.on(""));
    }
}
