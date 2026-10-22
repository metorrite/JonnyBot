package com.younglings.bot.fun;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * The three free, key-less public APIs behind /cat, /dog and /dadjoke. Every answer is kept in a small per-kind cache, so when an API is
 * down or slow the command serves something it fetched earlier instead of failing, and only when nothing was ever fetched does it say
 * so (the caller words that politely). Nothing here is fetched for a server that has the commands switched off, because a switched-off
 * command is not even registered there.
 */
@BService
public class FunApis {
    private static final Logger log = LoggerFactory.getLogger(FunApis.class);
    static final int CACHE_SIZE = 25;
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    public enum Kind {
        CAT("https://api.thecatapi.com/v1/images/search", json -> DataArray.fromJson(json).getObject(0).getString("url")),
        DOG("https://dog.ceo/api/breeds/image/random", json -> DataObject.fromJson(json).getString("message")),
        DAD_JOKE("https://icanhazdadjoke.com/", json -> DataObject.fromJson(json).getString("joke"));

        private final String url;
        private final Function<String, String> parse;

        Kind(String url, Function<String, String> parse) {
            this.url = url;
            this.parse = parse;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Deque<String> cat = new ArrayDeque<>();
    private final Deque<String> dog = new ArrayDeque<>();
    private final Deque<String> joke = new ArrayDeque<>();
    private final RandomGenerator random = RandomGenerator.getDefault();

    /** A fresh answer, or (when the API fails) a random earlier one, or empty if there has never been one. */
    public Optional<String> get(Kind kind) {
        Deque<String> cache = cacheOf(kind);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(kind.url)).timeout(TIMEOUT)
                    .header("Accept", "application/json").header("User-Agent", "JonnyBot (https://jonnybot.rsyounglings.com)").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String value = kind.parse.apply(response.body());
                if (value != null && !value.isBlank()) {
                    remember(cache, value);
                    return Optional.of(value);
                }
            }
            log.warn("{} answered {}", kind, response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("{} could not be fetched: {}", kind, e.toString());
        }
        return fromCache(cache);
    }

    private void remember(Deque<String> cache, String value) {
        synchronized (cache) {
            cache.remove(value);
            cache.addFirst(value);
            while (cache.size() > CACHE_SIZE) cache.removeLast();
        }
    }

    private Optional<String> fromCache(Deque<String> cache) {
        synchronized (cache) {
            if (cache.isEmpty()) return Optional.empty();
            return Optional.of(cache.stream().skip(random.nextInt(cache.size())).findFirst().orElseThrow());
        }
    }

    private Deque<String> cacheOf(Kind kind) {
        return switch (kind) {
            case CAT -> cat;
            case DOG -> dog;
            case DAD_JOKE -> joke;
        };
    }
}
