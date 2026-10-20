package com.younglings.bot.lookup;

import com.younglings.bot.util.TtlCache;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one polite way the RuneScape lookups (prices, event timers, boss and quest info) fetch text from a public website: an identifying
 * User-Agent, short timeouts, at most one request per host every {@value #MIN_GAP_MILLIS} ms, a body cap, and a cache so that many servers asking
 * for the same thing within a few minutes cost one request. A failed fetch is never cached and comes back empty, so callers say "the source isn't
 * answering right now" instead of crashing.
 */
@BService
public class LookupHttp {
    private static final Logger log = LoggerFactory.getLogger(LookupHttp.class);

    static final String USER_AGENT = "JonnyBot/1.0 (Discord bot for RuneScape clans; " + com.younglings.bot.config.BotConfig.getInstance().getJonnybotUrl() + ")";
    private static final long MIN_GAP_MILLIS = 250;
    private static final int MAX_BODY_CHARS = 2_000_000;

    private Function<String, Optional<String>> fetcher;
    private final TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(15));
    private final Object gate = new Object();
    private long lastRequestAt;

    public LookupHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.fetcher = url -> fetchLive(client, url);
    }

    /** For tests: a client that answers from {@code fetcher} instead of the network. (A second constructor would confuse the service container.) */
    static LookupHttp withFetcher(Function<String, Optional<String>> fetcher) {
        LookupHttp http = new LookupHttp();
        http.fetcher = fetcher;
        return http;
    }

    /** The body at {@code url}, from the cache if it was fetched in the last 15 minutes. Empty if the source is down, slow, or answered with an error. */
    public Optional<String> get(String url) {
        Optional<String> cached = cache.get(url);
        if (cached.isPresent()) return cached;
        Optional<String> fresh = fetcher.apply(url);
        fresh.ifPresent(body -> cache.put(url, body));
        return fresh;
    }

    private Optional<String> fetchLive(HttpClient client, String url) {
        synchronized (gate) {
            long wait = lastRequestAt + MIN_GAP_MILLIS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json, text/plain").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.info("Lookup source answered {} for {}", response.statusCode(), url);
                return Optional.empty();
            }
            String body = response.body();
            return body == null || body.length() > MAX_BODY_CHARS ? Optional.empty() : Optional.of(body);
        } catch (IOException | IllegalArgumentException e) {
            log.info("Lookup source unreachable for {}: {}", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
