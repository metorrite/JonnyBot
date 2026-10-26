package com.younglings.bot.lookup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Grand Exchange prices for RuneScape 3 from the Weird Gloop exchange API (the wiki's price source): the latest price and volume, plus a 7 and 30
 * day change worked out from the last 90 days of history. Item names for the autocomplete come from the RuneScape wiki's search. Every answer goes
 * through {@link LookupHttp}, so it is cached and polite; when a source is down the result says so rather than guessing.
 */
@BService
public class GePriceService {
    static final String EXCHANGE = "https://api.weirdgloop.org/exchange/history/rs/";
    static final String WIKI_API = "https://runescape.wiki/api.php";

    /** The change since {@code days} ago as a percentage, or null when the history doesn't reach back that far. */
    public record Trend(int days, long thenPrice, Double percent) {}

    public record GePrice(String name, String id, long price, long volume, Instant at, Trend week, Trend month) {
        public String wikiUrl() {
            return "https://runescape.wiki/w/" + URLEncoder.encode(name.replace(' ', '_'), StandardCharsets.UTF_8).replace("%2F", "/").replace("%28", "(").replace("%29", ")");
        }
    }

    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public record Outcome(Status status, GePrice price) {}

    private final LookupHttp http;

    public GePriceService(LookupHttp http) {
        this.http = http;
    }

    /** The price of the item with this exact name (any case), or why there isn't one. */
    public Outcome lookup(String itemName) {
        String name = itemName == null ? "" : itemName.strip();
        if (name.isEmpty() || name.length() > 80) return new Outcome(Status.NOT_FOUND, null);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        Optional<String> latest = http.get(EXCHANGE + "latest?name=" + encoded);
        if (latest.isEmpty()) return new Outcome(Status.UNAVAILABLE, null);
        Optional<GePrice> now = parseLatest(latest.get());
        if (now.isEmpty()) return new Outcome(Status.NOT_FOUND, null);
        GePrice price = now.get();
        // The history is a bonus: if it fails the price is still shown, just without the trend.
        Optional<String> history = http.get(EXCHANGE + "last90d?name=" + encoded);
        if (history.isPresent()) price = withTrends(price, parseHistory(history.get()));
        return new Outcome(Status.FOUND, price);
    }

    /** Just the latest price of the item with this exact name, in coins: one request, no history. Empty if there isn't one or the wiki can't be reached. */
    public Optional<Long> latestPrice(String itemName) {
        String name = itemName == null ? "" : itemName.strip();
        if (name.isEmpty() || name.length() > 80) return Optional.empty();
        return http.get(EXCHANGE + "latest?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)).flatMap(GePriceService::parseLatest).map(GePrice::price);
    }

    /** Up to {@code limit} item names starting with or close to what was typed. Empty if the wiki can't be reached. */
    public List<String> suggest(String typed, int limit) {
        String text = typed == null ? "" : typed.strip();
        if (text.length() < 2 || text.length() > 60) return List.of();
        Optional<String> body = http.get(WIKI_API + "?action=opensearch&limit=" + Math.min(limit, 25) + "&namespace=0&format=json&search=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
        return body.map(GePriceService::parseOpenSearch).orElse(List.of());
    }

    /** A watched item for the clan website's "GE price watch" widget (T-592): the price, the 7-day change and one price a day for the last 7 days. */
    public record Watched(GePrice price, List<Long> week) {}

    /**
     * The item with this exact name (any case) for the price watch: its price and 7-day change, and the last 7 days as one price a day (the
     * newest point of each day, oldest first) for the sparkline. Empty when there's no such item or the source is down; both are cached.
     */
    public Optional<Watched> watch(String itemName) {
        String name = itemName == null ? "" : itemName.strip();
        if (name.isEmpty() || name.length() > 80) return Optional.empty();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        Optional<GePrice> now = http.get(EXCHANGE + "latest?name=" + encoded).flatMap(GePriceService::parseLatest);
        if (now.isEmpty()) return Optional.empty();
        List<Point> history = http.get(EXCHANGE + "last90d?name=" + encoded).map(GePriceService::parseHistory).orElse(List.of());
        GePrice price = history.isEmpty() ? now.get() : withTrends(now.get(), history);
        Instant reference = price.at() == null ? Instant.now() : price.at();
        return Optional.of(new Watched(price, daily(history, reference, 7)));
    }

    /** The newest price of each of the last {@code days} UTC days up to {@code now} (a day with no point repeats the one before), oldest first. */
    static List<Long> daily(List<Point> history, Instant now, int days) {
        List<Long> out = new ArrayList<>();
        long today = Math.floorDiv(now.toEpochMilli(), 86_400_000L);
        Long carry = null;
        int i = 0;
        // the last point before the first day carries in, so a quiet first day still has a price
        long firstDay = today - days + 1;
        for (; i < history.size() && Math.floorDiv(history.get(i).millis(), 86_400_000L) < firstDay; i++) carry = history.get(i).price();
        for (long day = firstDay; day <= today; day++) {
            for (; i < history.size() && Math.floorDiv(history.get(i).millis(), 86_400_000L) == day; i++) carry = history.get(i).price();
            if (carry != null) out.add(carry);
        }
        return out;
    }

    // ---------- parsing (public-static so tests can feed saved responses) ----------

    record Point(long millis, long price) {}

    static Optional<GePrice> parseLatest(String json) {
        try {
            DataObject root = DataObject.fromJson(json);
            if (root.hasKey("success") && !root.getBoolean("success", true)) return Optional.empty();
            for (String name : root.keys()) {
                if (!root.isType(name, net.dv8tion.jda.api.utils.data.DataType.OBJECT)) continue;
                DataObject item = root.getObject(name);
                if (!item.hasKey("price")) continue;
                Instant at = item.hasKey("timestamp") ? Instant.parse(item.getString("timestamp")) : null;
                return Optional.of(new GePrice(name, item.getString("id", ""), item.getLong("price"), item.getLong("volume", 0), at, null, null));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static List<Point> parseHistory(String json) {
        List<Point> points = new ArrayList<>();
        try {
            DataObject root = DataObject.fromJson(json);
            for (String name : root.keys()) {
                if (!root.isType(name, net.dv8tion.jda.api.utils.data.DataType.ARRAY)) continue;
                DataArray array = root.getArray(name);
                for (int i = 0; i < array.length(); i++) {
                    DataObject p = array.getObject(i);
                    points.add(new Point(p.getLong("timestamp"), p.getLong("price")));
                }
                break;
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        points.sort((a, b) -> Long.compare(a.millis(), b.millis()));
        return points;
    }

    static List<String> parseOpenSearch(String json) {
        try {
            DataArray root = DataArray.fromJson(json);
            DataArray names = root.getArray(1);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < names.length(); i++) out.add(names.getString(i));
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The newest history point that is at least {@code days} old, compared with the current price. */
    static Trend trend(long nowPrice, List<Point> history, Instant now, int days) {
        long cutoff = now.minus(Duration.ofDays(days)).toEpochMilli();
        Point base = null;
        for (Point p : history) if (p.millis() <= cutoff) base = p;
        if (base == null || base.price() <= 0) return new Trend(days, 0, null);
        return new Trend(days, base.price(), (nowPrice - base.price()) * 100.0 / base.price());
    }

    private static GePrice withTrends(GePrice p, List<Point> history) {
        Instant reference = p.at() == null ? Instant.now() : p.at();
        return new GePrice(p.name(), p.id(), p.price(), p.volume(), p.at(), trend(p.price(), history, reference, 7), trend(p.price(), history, reference, 30));
    }
}
