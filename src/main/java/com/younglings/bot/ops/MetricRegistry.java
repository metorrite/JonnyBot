package com.younglings.bot.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters and histograms in memory, written out in the Prometheus text format (version 0.0.4) by hand, so the bot needs no metrics
 * library (T-674). Gauges are not kept here: they are read when {@link #render} is called.
 * <p>
 * Label values must come from a small set (a route pattern, never a raw path): each family keeps at most {@link #MAX_SERIES} label sets and
 * drops new ones after that, counting them in {@code rc_metrics_dropped_series_total}.
 */
public final class MetricRegistry {
    /** Request latency buckets, in seconds. */
    public static final double[] LATENCY_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    static final int MAX_SERIES = 300;

    /** A value read at scrape time. */
    public record Gauge(String name, String help, Map<String, String> labels, double value) {
        public static Gauge of(String name, String help, double value) {
            return new Gauge(name, help, Map.of(), value);
        }
    }

    private sealed interface Family permits Counters, Histograms {}

    private record Counters(String help, Map<String, LongAdder> series) implements Family {}

    private record Histograms(String help, double[] buckets, Map<String, Histogram> series) implements Family {}

    private static final class Histogram {
        final AtomicLongArray counts;
        final DoubleAdder sum = new DoubleAdder();
        final LongAdder count = new LongAdder();

        Histogram(int buckets) {
            counts = new AtomicLongArray(buckets);
        }
    }

    private final Map<String, Family> families = new ConcurrentSkipListMap<>();
    private final LongAdder dropped = new LongAdder();

    /** Adds one to a counter. */
    public void inc(String name, String help, Map<String, String> labels) {
        add(name, help, labels, 1);
    }

    public void add(String name, String help, Map<String, String> labels, long amount) {
        Family f = families.computeIfAbsent(name, n -> new Counters(help, new ConcurrentHashMap<>()));
        if (!(f instanceof Counters c)) throw new IllegalArgumentException(name + " is not a counter");
        LongAdder adder = series(c.series(), labels(labels), LongAdder::new);
        if (adder != null) adder.add(amount);
    }

    /** Records one observation (seconds for latency) in a histogram with the given buckets. */
    public void observe(String name, String help, double[] buckets, Map<String, String> labels, double value) {
        Family f = families.computeIfAbsent(name, n -> new Histograms(help, buckets.clone(), new ConcurrentHashMap<>()));
        if (!(f instanceof Histograms h)) throw new IllegalArgumentException(name + " is not a histogram");
        Histogram series = series(h.series(), labels(labels), () -> new Histogram(h.buckets().length));
        if (series == null) return;
        for (int i = 0; i < h.buckets().length; i++) {
            if (value <= h.buckets()[i]) {
                series.counts.incrementAndGet(i);
                break;
            }
        }
        series.sum.add(value);
        series.count.increment();
    }

    /** A counter's current value (0 when never counted), for the ops alerts and the tests. */
    public long counter(String name, Map<String, String> labels) {
        Family f = families.get(name);
        if (!(f instanceof Counters c)) return 0;
        LongAdder adder = c.series().get(labels(labels));
        return adder == null ? 0 : adder.sum();
    }

    /** The sum of a counter over every label set. */
    public long counterTotal(String name) {
        Family f = families.get(name);
        if (!(f instanceof Counters c)) return 0;
        long total = 0;
        for (LongAdder a : c.series().values()) total += a.sum();
        return total;
    }

    /** The sum of a counter over the label sets where {@code label} is {@code value}. */
    public long counterWhere(String name, String label, String value) {
        Family f = families.get(name);
        if (!(f instanceof Counters c)) return 0;
        String pair = label + "=\"" + escape(value) + "\"";
        long total = 0;
        for (Map.Entry<String, LongAdder> e : c.series().entrySet()) {
            if (e.getKey().contains("{" + pair) || e.getKey().contains("," + pair)) total += e.getValue().sum();
        }
        return total;
    }

    private <T> T series(Map<String, T> map, String key, java.util.function.Supplier<T> make) {
        T existing = map.get(key);
        if (existing != null) return existing;
        if (map.size() >= MAX_SERIES) {
            dropped.increment();
            return null;
        }
        return map.computeIfAbsent(key, k -> make.get());
    }

    /** Everything in the Prometheus text format: the kept counters and histograms, then {@code gauges}. */
    public String render(List<Gauge> gauges) {
        StringBuilder out = new StringBuilder(4096);
        for (Map.Entry<String, Family> e : families.entrySet()) {
            String name = e.getKey();
            if (e.getValue() instanceof Counters c) {
                header(out, name, c.help(), "counter");
                new TreeMap<>(c.series()).forEach((labels, v) -> out.append(name).append(labels).append(' ').append(v.sum()).append('\n'));
            } else if (e.getValue() instanceof Histograms h) {
                header(out, name, h.help(), "histogram");
                new TreeMap<>(h.series()).forEach((labels, s) -> {
                    long cumulative = 0;
                    for (int i = 0; i < h.buckets().length; i++) {
                        cumulative += s.counts.get(i);
                        out.append(name).append("_bucket").append(withLe(labels, number(h.buckets()[i]))).append(' ').append(cumulative).append('\n');
                    }
                    out.append(name).append("_bucket").append(withLe(labels, "+Inf")).append(' ').append(s.count.sum()).append('\n');
                    out.append(name).append("_sum").append(labels).append(' ').append(number(s.sum.sum())).append('\n');
                    out.append(name).append("_count").append(labels).append(' ').append(s.count.sum()).append('\n');
                });
            }
        }
        header(out, "rc_metrics_dropped_series_total", "Label sets not kept because a family already had " + MAX_SERIES + ".", "counter");
        out.append("rc_metrics_dropped_series_total ").append(dropped.sum()).append('\n');
        Map<String, List<Gauge>> byName = new TreeMap<>();
        for (Gauge g : gauges) byName.computeIfAbsent(g.name(), k -> new ArrayList<>()).add(g);
        byName.forEach((name, list) -> {
            header(out, name, list.get(0).help(), "gauge");
            for (Gauge g : list) out.append(name).append(labels(g.labels())).append(' ').append(number(g.value())).append('\n');
        });
        return out.toString();
    }

    private static void header(StringBuilder out, String name, String help, String type) {
        out.append("# HELP ").append(name).append(' ').append(help.replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    /** {@code {a="1",b="2"}} with the names sorted and the values escaped; empty for no labels. */
    static String labels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return "";
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, String> e : new TreeMap<>(labels).entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
        }
        return b.append('}').toString();
    }

    private static String withLe(String labels, String le) {
        if (labels.isEmpty()) return "{le=\"" + le + "\"}";
        return labels.substring(0, labels.length() - 1) + ",le=\"" + le + "\"}";
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String number(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%s", v);
    }
}
