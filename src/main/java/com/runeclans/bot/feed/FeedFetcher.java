package com.runeclans.bot.feed;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Reads a feed over HTTP, politely: an identifying User-Agent, short timeouts, and a cap on how much is read. */
@BService
public class FeedFetcher {
    static final String USER_AGENT = "JonnyBot/1.0 (Discord feed poster; +" + com.runeclans.bot.config.BotConfig.getInstance().getJonnybotUrl() + ")";
    static final int MAX_BYTES = 1_000_000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The body of the feed. Throws {@link IOException} when it can't be read (down, rate limited, too big). */
    public String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT)
                .header("Accept", "application/atom+xml, application/xml, application/json;q=0.9, */*;q=0.5").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + request.uri().getHost());
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Feed is larger than " + MAX_BYTES + " bytes");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
